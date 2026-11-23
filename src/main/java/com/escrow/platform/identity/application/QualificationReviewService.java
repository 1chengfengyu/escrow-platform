package com.escrow.platform.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.SecurityLog;
import com.escrow.platform.identity.api.QualificationListItem;
import com.escrow.platform.identity.api.QualificationReviewRequest;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.PlatformEmployerQualification;
import com.escrow.platform.identity.domain.PlatformUser;
import com.escrow.platform.identity.domain.ReviewStatus;
import com.escrow.platform.identity.infrastructure.mapper.PlatformEmployerQualificationMapper;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理员审核雇主资质（FR-ADMIN.QUAL.01，状态变更的唯一入口，FR-ACCT.QUAL.02）。
 *
 * <p>列表固定 50 条、按 {@code submitted_at} 升序（先到先审），<b>不做分页</b>：
 * 分页组件 V2 才随 jsqlparser 引入（docs/03），这里自造一套分页参数就是第二套方言。</p>
 *
 * <p>审核用「条件更新 + 校验影响行数」而不是「先 SELECT 判断再 UPDATE」：
 * 两个管理员同时点通过时，后到的那个必须收到 409，而不是把前一个的结论覆盖掉
 * 再写一条 {@code APPROVED} 的安全日志（docs/tech-stack.md）。</p>
 *
 * <p>只改状态，不发通知：通知域属 V6。</p>
 *
 * @since V1
 */
@Service
@RequiredArgsConstructor
public class QualificationReviewService {

    private static final String RESOURCE = "identity:qualification";
    private static final String ADMIN_ONLY_MESSAGE = "仅管理员可审核资质";
    private static final String INVALID_PARAMETER_MESSAGE = "请求参数不合法";
    private static final String STATUS_NOT_PENDING_MESSAGE = "该资质不处于待审核状态";
    private static final String NOT_FOUND_MESSAGE = "目标用户没有资质记录";
    private static final String STATUS_PARAM_MESSAGE = "status 只允许 PENDING / APPROVED / REJECTED";

    /** 管理端列表固定上限：先到先审，不做分页（v1-c5 §4.3）。 */
    private static final int LIST_LIMIT = 50;

    private final PlatformEmployerQualificationMapper qualificationMapper;
    private final PlatformUserMapper platformUserMapper;
    private final SecurityLog securityLog;
    private final AuditRecorder auditRecorder;

    private final QualificationValidator validator = new QualificationValidator();

    /**
     * 按状态列出待审 / 已审资质（FR-ADMIN.QUAL.01）。
     *
     * @param current 会话主体，必须是 ADMIN
     * @param status  筛选状态，缺省 {@code PENDING}；只接受 PENDING / APPROVED / REJECTED
     * @return 至多 50 条，按提交时间升序
     * @throws ApiException 403 非管理员 / 400 状态参数非法
     */
    public List<QualificationListItem> list(LoginUser current, String status) {
        requireAdmin(current);
        ReviewStatus filter = parseListStatus(status);

        List<PlatformEmployerQualification> rows = qualificationMapper.selectList(
                new LambdaQueryWrapper<PlatformEmployerQualification>()
                        .eq(PlatformEmployerQualification::getReviewStatus, filter)
                        .orderByAsc(PlatformEmployerQualification::getSubmittedAt)
                        // 固定上限，常量拼接不引入用户输入；分页组件 V2 才引，这里不做第二套方言
                        .last("LIMIT " + LIST_LIMIT));

        Map<Long, String> nicknameById = nicknames(rows);
        return rows.stream()
                .map(row -> new QualificationListItem(
                        row.getUserId(),
                        nicknameById.get(row.getUserId()),
                        row.getSubjectType().name(),
                        row.getSubjectName(),
                        row.getIdLast4(),
                        row.getSubmittedAt()))
                .toList();
    }

    /**
     * 审核资质：置为已通过或已驳回（FR-ADMIN.QUAL.01）。
     *
     * @param current 会话主体，必须是 ADMIN
     * @param userId  目标账号 ID
     * @param request 审核请求
     * @throws ApiException 403 非管理员 / 400 字段不合法 / 404 无资质记录 / 409 不处于待审核
     */
    @Transactional
    public void review(LoginUser current, Long userId, QualificationReviewRequest request) {
        requireAdmin(current);

        Map<String, String> fields = validator.validateReview(request);
        if (!fields.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, INVALID_PARAMETER_MESSAGE,
                    Map.of("fields", fields));
        }

        ReviewStatus decision = ReviewStatus.valueOf(request.decision().trim());
        String reason = normalizeReason(request.reason());

        PlatformEmployerQualification existing = qualificationMapper.selectOne(
                new LambdaQueryWrapper<PlatformEmployerQualification>()
                        .eq(PlatformEmployerQualification::getUserId, userId));
        if (existing == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, NOT_FOUND_MESSAGE);
        }

        // 条件更新：review_status = PENDING 是并发防线，影响行数为 0 即「已被别人审过」或「本就不在待审」
        int updated = qualificationMapper.update(null, new LambdaUpdateWrapper<PlatformEmployerQualification>()
                .eq(PlatformEmployerQualification::getUserId, userId)
                .eq(PlatformEmployerQualification::getReviewStatus, ReviewStatus.PENDING)
                .set(PlatformEmployerQualification::getReviewStatus, decision)
                .set(PlatformEmployerQualification::getReviewerId, current.userId())
                .set(PlatformEmployerQualification::getReviewReason, reason)
                .set(PlatformEmployerQualification::getReviewedAt, LocalDateTime.now()));
        if (updated == 0) {
            // SRS 没有「重复审核」的业务码，按 docs/decisions.md #30 不许自创，用协议级 CONFLICT
            throw new ApiException(ErrorCode.CONFLICT, STATUS_NOT_PENDING_MESSAGE);
        }

        securityLog.log(SecurityLog.ACTION_QUALIFICATION_REVIEW, RESOURCE, current.account(), decision.name());
        auditRecorder.record("QUALIFICATION", String.valueOf(userId), decision.name(),
                ReviewStatus.PENDING.name(), decision.name(), reason);
    }

    /** 管理端接口只对 ADMIN 开放；身份由会话决定，不接受请求参数覆盖。 */
    private static void requireAdmin(LoginUser current) {
        if (current == null || current.currentIdentity() != IdentityType.ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN, ADMIN_ONLY_MESSAGE);
        }
    }

    /** 白名单式解析：缺省 PENDING；UNSUBMITTED 与未知值都按参数错误处理（它不是存储态）。 */
    private static ReviewStatus parseListStatus(String status) {
        String value = status == null || status.isBlank()
                ? ReviewStatus.PENDING.name() : status.trim();
        if (ReviewStatus.PENDING.name().equals(value)) {
            return ReviewStatus.PENDING;
        }
        if (ReviewStatus.APPROVED.name().equals(value)) {
            return ReviewStatus.APPROVED;
        }
        if (ReviewStatus.REJECTED.name().equals(value)) {
            return ReviewStatus.REJECTED;
        }
        throw new ApiException(ErrorCode.INVALID_PARAMETER, STATUS_PARAM_MESSAGE);
    }

    /** 空白理由统一转 {@code null}：通过时传空串，不应在库里留下「有理由但是空」的行。 */
    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.trim();
    }

    /** 批量取昵称，避免 50 行逐条查账号（列表固定上限，但 N+1 没理由留着）。 */
    private Map<Long, String> nicknames(List<PlatformEmployerQualification> rows) {
        List<Long> userIds = rows.stream()
                .map(PlatformEmployerQualification::getUserId)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return platformUserMapper.selectList(new LambdaQueryWrapper<PlatformUser>()
                        .in(PlatformUser::getId, userIds))
                .stream()
                .collect(Collectors.toMap(PlatformUser::getId, PlatformUser::getNickname));
    }
}
