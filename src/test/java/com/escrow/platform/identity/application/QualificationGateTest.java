package com.escrow.platform.identity.application;

import com.escrow.platform.identity.domain.PlatformEmployerQualification;
import com.escrow.platform.identity.domain.ReviewStatus;
import com.escrow.platform.identity.infrastructure.mapper.PlatformEmployerQualificationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 资质准入开关单测（v1-c5 §4.5 / §8 第 4 条，FR-ACCT.QUAL.03）。
 *
 * <p>重点验证「开关关闭时恒 true 且不查库」：这是本类存在的性能理由，
 * 只断言返回值不断言交互的话，退化成每次发布多一条 SQL 也不会被发现。</p>
 *
 * @since V1
 */
@ExtendWith(MockitoExtension.class)
class QualificationGateTest {

    @Mock
    private PlatformEmployerQualificationMapper qualificationMapper;

    /** 关 + 无记录：恒 true，且一次库都不查。 */
    @Test
    void disabledGateShouldAllowWithoutQuerying() {
        QualificationGate gate = new QualificationGate(qualificationMapper, false);

        assertThat(gate.isPublishEligible(1001L)).isTrue();
        verifyNoInteractions(qualificationMapper);
    }

    /** 关 + 已驳回：开关关闭时不看审核结果，仍为 true。 */
    @Test
    void disabledGateShouldAllowEvenIfRejected() {
        QualificationGate gate = new QualificationGate(qualificationMapper, false);

        assertThat(gate.isPublishEligible(1001L)).isTrue();
        verifyNoInteractions(qualificationMapper);
    }

    /** 开 + 已通过：true。 */
    @Test
    void enabledGateShouldAllowApproved() {
        when(qualificationMapper.selectOne(any())).thenReturn(record(ReviewStatus.APPROVED));
        QualificationGate gate = new QualificationGate(qualificationMapper, true);

        assertThat(gate.isPublishEligible(1001L)).isTrue();
    }

    /** 开 + 待审核：false。 */
    @Test
    void enabledGateShouldDenyPending() {
        when(qualificationMapper.selectOne(any())).thenReturn(record(ReviewStatus.PENDING));
        QualificationGate gate = new QualificationGate(qualificationMapper, true);

        assertThat(gate.isPublishEligible(1001L)).isFalse();
    }

    /** 开 + 无记录：按 UNSUBMITTED 处理 → false。 */
    @Test
    void enabledGateShouldDenyWithoutRecord() {
        when(qualificationMapper.selectOne(any())).thenReturn(null);
        QualificationGate gate = new QualificationGate(qualificationMapper, true);

        assertThat(gate.isPublishEligible(1001L)).isFalse();
    }

    private static PlatformEmployerQualification record(ReviewStatus status) {
        PlatformEmployerQualification q = new PlatformEmployerQualification();
        q.setUserId(1001L);
        q.setReviewStatus(status);
        return q;
    }
}
