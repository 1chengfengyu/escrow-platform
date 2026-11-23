package com.escrow.platform.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.identity.domain.PlatformEmployerQualification;
import com.escrow.platform.identity.domain.ReviewStatus;
import com.escrow.platform.identity.infrastructure.mapper.PlatformEmployerQualificationMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 「资质准入」开关（FR-ACCT.QUAL.03，TBD-04）。
 *
 * <p>配置项 {@code escrow.qualification.required}，<b>默认 {@code false}</b>：
 * 开关关闭时恒返回 {@code true} 且<b>不查库</b>——否则每次发布任务都多一次 SQL，
 * 而这次 SQL 的结果在开关关闭时根本不参与判断。</p>
 *
 * <p>开关打开时仅 {@code review_status='APPROVED'} 为真；无记录按 {@code UNSUBMITTED} 处理 → {@code false}。</p>
 *
 * <p>V2 发布任务时直接调用 {@link #isPublishEligible(Long)}，不要另写一份判断：
 * 两份判断迟早会出现「接口显示可发布、实际发布被拒」的双真相源。</p>
 *
 * @since V1
 */
@Component
public class QualificationGate {

    private final PlatformEmployerQualificationMapper qualificationMapper;

    /** 资质准入是否启用；默认关闭，可用环境变量 {@code ESCROW_QUALIFICATION_REQUIRED=true} 打开。 */
    private final boolean required;

    public QualificationGate(PlatformEmployerQualificationMapper qualificationMapper,
                             @Value("${escrow.qualification.required:false}") boolean required) {
        this.qualificationMapper = qualificationMapper;
        this.required = required;
    }

    /**
     * 判断某账号是否满足发布任务的资质准入。
     *
     * @param userId 账号 ID
     * @return 开关关闭恒为 {@code true}；开关打开时仅资质「已通过」为 {@code true}
     */
    public boolean isPublishEligible(Long userId) {
        if (!required) {
            return true;
        }
        PlatformEmployerQualification qualification = qualificationMapper.selectOne(
                new LambdaQueryWrapper<PlatformEmployerQualification>()
                        .eq(PlatformEmployerQualification::getUserId, userId));
        return qualification != null && qualification.getReviewStatus() == ReviewStatus.APPROVED;
    }
}
