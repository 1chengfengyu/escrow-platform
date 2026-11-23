package com.escrow.platform.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.SecurityLog;
import com.escrow.platform.identity.api.QualificationSubmitRequest;
import com.escrow.platform.identity.api.QualificationView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.PlatformEmployerQualification;
import com.escrow.platform.identity.domain.ReviewStatus;
import com.escrow.platform.identity.domain.SubjectType;
import com.escrow.platform.identity.infrastructure.mapper.PlatformEmployerQualificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 雇主资质的提交与查询（FR-ACCT.QUAL.01 / QUAL.02 / QUAL.03）。
 *
 * <p>提交语义：首次 INSERT、再次 UPDATE。重复提交等于重新送审——
 * 必须把 {@code reviewer_id / review_reason / reviewed_at} 一起清空，
 * 否则旧的「已驳回」理由会挂在新一轮 {@code PENDING} 上，雇主看到的是上一轮的解释。</p>
 *
 * <p>「仅管理员可变更状态」（FR-ACCT.QUAL.02）在代码里的落地点有两处：
 * 提交只允许写 {@code PENDING}（本类），审核是唯一能写 {@code APPROVED / REJECTED} 的入口
 * （{@link QualificationReviewService}）。</p>
 *
 * @since V1
 */
@Service
@RequiredArgsConstructor
public class QualificationService {

    /** 安全日志资源名，固定为域:资源，便于 grep（v1-c5 §6）。 */
    private static final String RESOURCE = "identity:qualification";

    private static final String NOT_EMPLOYER_MESSAGE = "仅雇主身份可提交资质";
    private static final String INVALID_PARAMETER_MESSAGE = "请求参数不合法";

    private final PlatformEmployerQualificationMapper qualificationMapper;
    private final SecurityLog securityLog;
    private final QualificationGate qualificationGate;

    /** 无状态校验器，直接持有实例；资质流程只有这一处使用。 */
    private final QualificationValidator validator = new QualificationValidator();

    /**
     * 提交 / 重新提交雇主资质（FR-ACCT.QUAL.01）。
     *
     * @param current 会话主体
     * @param request 提交请求
     * @throws ApiException 403 非雇主身份 / 400 字段不合法（{@code data.fields}）
     */
    @Transactional
    public void submit(LoginUser current, QualificationSubmitRequest request) {
        // FR-ACCT.QUAL.01 只对雇主开放：当前会话身份不是 EMPLOYER 时拒绝，并留一条安全日志
        if (current == null || current.currentIdentity() != IdentityType.EMPLOYER) {
            securityLog.log(SecurityLog.ACTION_QUALIFICATION_SUBMIT, RESOURCE,
                    current == null ? null : current.account(), SecurityLog.RESULT_DENIED_NOT_EMPLOYER);
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_EMPLOYER_MESSAGE);
        }

        Map<String, String> fields = validator.validateSubmit(request);
        if (!fields.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, INVALID_PARAMETER_MESSAGE,
                    Map.of("fields", fields));
        }

        SubjectType subjectType = SubjectType.valueOf(request.subjectType().trim());
        String subjectName = request.subjectName().trim();
        String idLast4 = request.idLast4().trim();
        LocalDateTime now = LocalDateTime.now();

        PlatformEmployerQualification existing = findByUserId(current.userId());
        if (existing == null) {
            PlatformEmployerQualification qualification = new PlatformEmployerQualification();
            qualification.setUserId(current.userId());
            qualification.setSubjectType(subjectType);
            qualification.setSubjectName(subjectName);
            qualification.setIdLast4(idLast4);
            qualification.setReviewStatus(ReviewStatus.PENDING);
            qualification.setSubmittedAt(now);
            qualificationMapper.insert(qualification);
        } else {
            // 重新提交 = 重新送审：状态回 PENDING，并把上一轮的审核人 / 理由 / 时间全部清空。
            // 必须用 UpdateWrapper 显式 set null —— updateById 会忽略 null 字段，旧理由清不掉。
            qualificationMapper.update(null, new LambdaUpdateWrapper<PlatformEmployerQualification>()
                    .eq(PlatformEmployerQualification::getId, existing.getId())
                    .set(PlatformEmployerQualification::getSubjectType, subjectType)
                    .set(PlatformEmployerQualification::getSubjectName, subjectName)
                    .set(PlatformEmployerQualification::getIdLast4, idLast4)
                    .set(PlatformEmployerQualification::getReviewStatus, ReviewStatus.PENDING)
                    .set(PlatformEmployerQualification::getSubmittedAt, now)
                    .set(PlatformEmployerQualification::getReviewerId, null)
                    .set(PlatformEmployerQualification::getReviewReason, null)
                    .set(PlatformEmployerQualification::getReviewedAt, null));
        }

        securityLog.log(SecurityLog.ACTION_QUALIFICATION_SUBMIT, RESOURCE, current.account(),
                SecurityLog.RESULT_OK);
    }

    /**
     * 查询当前账号的资质（FR-ACCT.QUAL.02 / QUAL.03）。
     *
     * <p>从未提交返回 {@code status=UNSUBMITTED} 且其余字段为空，<b>不返回 404</b>：
     * 未提交是正常状态，不是资源缺失。</p>
     *
     * @param current 会话主体
     * @return 资质视图，含 {@code publishEligible}
     */
    public QualificationView get(LoginUser current) {
        PlatformEmployerQualification qualification = findByUserId(current.userId());
        boolean publishEligible = qualificationGate.isPublishEligible(current.userId());

        if (qualification == null) {
            return new QualificationView(ReviewStatus.UNSUBMITTED.name(), null, null, null,
                    null, null, publishEligible);
        }
        return new QualificationView(
                qualification.getReviewStatus().name(),
                qualification.getSubjectType().name(),
                qualification.getSubjectName(),
                qualification.getIdLast4(),
                qualification.getReviewReason(),
                qualification.getReviewedAt(),
                publishEligible);
    }

    private PlatformEmployerQualification findByUserId(Long userId) {
        return qualificationMapper.selectOne(new LambdaQueryWrapper<PlatformEmployerQualification>()
                .eq(PlatformEmployerQualification::getUserId, userId));
    }
}
