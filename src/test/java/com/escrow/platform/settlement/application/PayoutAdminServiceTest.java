package com.escrow.platform.settlement.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.settlement.api.PayoutDecisionRequest;
import com.escrow.platform.settlement.domain.PayoutManualDecision;
import com.escrow.platform.settlement.domain.PayoutManualDecisionType;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutManualDecisionMapper;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人工处置台测试（v5-c4）：权限、状态、三选一校验与资金入口调用。
 *
 * @since V5
 */
@ExtendWith(MockitoExtension.class)
class PayoutAdminServiceTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);
    private static final LoginUser WORKER =
            new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Mock
    private PayoutOrderMapper payoutOrderMapper;
    @Mock
    private PayoutManualDecisionMapper payoutManualDecisionMapper;
    @Mock
    private PayoutTxService payoutTxService;
    @Mock
    private PayoutRuntimeConfig runtimeConfig;

    @Mock
    private com.escrow.platform.common.audit.application.AuditRecorder auditRecorder;
    @InjectMocks
    private PayoutAdminService service;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, PayoutOrder.class);
        TableInfoHelper.initTableInfo(assistant, PayoutManualDecision.class);
    }

    private PayoutOrder manualPayout() {
        PayoutOrder payout = new PayoutOrder();
        payout.setId(70L);
        payout.setSettlementId(80L);
        payout.setRequestNo("PO70");
        payout.setAttemptSeq(1);
        payout.setAmount(970L);
        payout.setStatus(PayoutStatus.PAYOUT_MANUAL_REVIEW);
        payout.setStatusEnteredAt(LocalDateTime.now().minusHours(2));
        return payout;
    }

    /** 非管理员一律 403。 */
    @Test
    void nonAdminShouldBeForbidden() {
        assertThatThrownBy(() -> service.decide(WORKER, 70L,
                new PayoutDecisionRequest(PayoutManualDecisionType.CONFIRM_ACTUAL, 960L, "核对")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(payoutTxService, never()).handleManualConfirm(anyLong(), anyLong());
    }

    /** 非待人工状态不得处置。 */
    @Test
    void nonManualReviewPayoutShouldBeRejected() {
        PayoutOrder payout = manualPayout();
        payout.setStatus(PayoutStatus.PAYOUT_SUCCESS);
        when(payoutOrderMapper.selectById(70L)).thenReturn(payout);

        assertThatThrownBy(() -> service.decide(ADMIN, 70L,
                new PayoutDecisionRequest(PayoutManualDecisionType.CONFIRM_ACTUAL, 960L, "核对")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    /** 按实际金额处置：落决策记录 + 走人工确认成功。 */
    @Test
    void confirmActualShouldRecordAndComplete() {
        PayoutOrder payout = manualPayout();
        when(payoutOrderMapper.selectById(70L)).thenReturn(payout);

        service.decide(ADMIN, 70L,
                new PayoutDecisionRequest(PayoutManualDecisionType.CONFIRM_ACTUAL, 960L, "以对账单为准"));

        ArgumentCaptor<PayoutManualDecision> captor =
                ArgumentCaptor.forClass(PayoutManualDecision.class);
        verify(payoutManualDecisionMapper).insert(captor.capture());
        assertThat(captor.getValue().getDecision()).isEqualTo(PayoutManualDecisionType.CONFIRM_ACTUAL);
        assertThat(captor.getValue().getActualAmount()).isEqualTo(960L);
        assertThat(captor.getValue().getOperatorId()).isEqualTo(9001L);
        verify(payoutTxService).handleManualConfirm(70L, 960L);
    }

    /** 零扣款：必须带凭据说明，实际金额固定 0。 */
    @Test
    void zeroPaymentShouldRequireRemarkAndUseZero() {
        PayoutOrder payout = manualPayout();
        when(payoutOrderMapper.selectById(70L)).thenReturn(payout);

        service.decide(ADMIN, 70L,
                new PayoutDecisionRequest(PayoutManualDecisionType.ZERO_PAYMENT, null, "凭据 ZP-001"));

        verify(payoutTxService).handleManualConfirm(70L, 0L);
    }

    /** 零扣款缺凭据：拒绝，不落任何记录。 */
    @Test
    void zeroPaymentWithoutRemarkShouldBeRejected() {
        when(payoutOrderMapper.selectById(70L)).thenReturn(manualPayout());

        assertThatThrownBy(() -> service.decide(ADMIN, 70L,
                new PayoutDecisionRequest(PayoutManualDecisionType.ZERO_PAYMENT, null, " ")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(payoutManualDecisionMapper, never()).insert(any(PayoutManualDecision.class));
    }

    /** 按实际金额处置缺金额：拒绝。 */
    @Test
    void confirmActualWithoutAmountShouldBeRejected() {
        when(payoutOrderMapper.selectById(70L)).thenReturn(manualPayout());

        assertThatThrownBy(() -> service.decide(ADMIN, 70L,
                new PayoutDecisionRequest(PayoutManualDecisionType.CONFIRM_ACTUAL, null, "核对")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    /** 实际金额超过请求金额：拒绝（超额出款不在本状态机内）。 */
    @Test
    void confirmActualAboveRequestedShouldBeRejected() {
        when(payoutOrderMapper.selectById(70L)).thenReturn(manualPayout());

        assertThatThrownBy(() -> service.decide(ADMIN, 70L,
                new PayoutDecisionRequest(PayoutManualDecisionType.CONFIRM_ACTUAL, 971L, "核对")))
                .isInstanceOf(ApiException.class);
    }

    /** 转差异：只落决策，不改状态、不触发分账。 */
    @Test
    void toDifferenceShouldOnlyRecordDecision() {
        when(payoutOrderMapper.selectById(70L)).thenReturn(manualPayout());

        service.decide(ADMIN, 70L,
                new PayoutDecisionRequest(PayoutManualDecisionType.TO_DIFFERENCE, null, "待日终对账"));

        ArgumentCaptor<PayoutManualDecision> captor =
                ArgumentCaptor.forClass(PayoutManualDecision.class);
        verify(payoutManualDecisionMapper).insert(captor.capture());
        assertThat(captor.getValue().getDecision()).isEqualTo(PayoutManualDecisionType.TO_DIFFERENCE);
        assertThat(captor.getValue().getActualAmount()).isNull();
        verify(payoutTxService, never()).handleManualConfirm(anyLong(), anyLong());
    }
}
