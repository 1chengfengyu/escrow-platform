package com.escrow.platform.engagement.application;

import com.escrow.platform.engagement.domain.AcceptanceDecision;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.settlement.application.PayoutService;
import com.escrow.platform.task.domain.SlotStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AcceptanceService} 的编排单测（FR-ACCEPT.MANUAL.01 / FR-ACCEPT.RESULT.02）。
 *
 * <p>验收接口必须返回「落库后的最终状态」：同步通道确认成功时名额已完结，
 * 不能把事务内的待结算快照原样返回（V7 实跑发现的缺陷）。</p>
 *
 * @since V7
 */
@ExtendWith(MockitoExtension.class)
class AcceptanceServiceTest {

    private static final Long CONTRACT_ID = 30001L;
    private static final Long SETTLEMENT_ID = 40001L;
    private static final Long PAYOUT_ID = 50001L;
    private static final LoginUser EMPLOYER =
            new LoginUser(1001L, "13800138000", UserRole.EMPLOYER, IdentityType.EMPLOYER);

    @Mock
    private AcceptanceTxService acceptanceTxService;

    @Mock
    private PayoutService payoutService;

    private AcceptanceService service;

    @BeforeEach
    void setUp() {
        service = new AcceptanceService(acceptanceTxService, payoutService);
    }

    /** 打款同步确认成功：返回的名额状态刷新为已完结。 */
    @Test
    void acceptShouldReturnCompletedSlotAfterPayoutSucceeded() {
        AcceptanceTxService.AcceptanceOutcome outcome = new AcceptanceTxService.AcceptanceOutcome(
                CONTRACT_ID, com.escrow.platform.engagement.domain.ContractStatus.CONTRACT_COMPLETED,
                SlotStatus.SLOT_PENDING_SETTLEMENT, null, SETTLEMENT_ID, PAYOUT_ID);
        when(acceptanceTxService.decide(EMPLOYER, CONTRACT_ID, AcceptanceDecision.PASS, null))
                .thenReturn(outcome);
        when(payoutService.dispatch(PAYOUT_ID)).thenReturn(true);

        AcceptanceTxService.AcceptanceOutcome result =
                service.accept(EMPLOYER, CONTRACT_ID, AcceptanceDecision.PASS, null);

        assertThat(result.slotStatus()).isEqualTo(SlotStatus.SLOT_COMPLETED);
        assertThat(result.settlementId()).isEqualTo(SETTLEMENT_ID);
        assertThat(result.payoutId()).isEqualTo(PAYOUT_ID);
    }

    /** 打款未知态：保留待结算，不能假装已完结。 */
    @Test
    void acceptShouldKeepPendingSettlementWhenPayoutUnknown() {
        AcceptanceTxService.AcceptanceOutcome outcome = new AcceptanceTxService.AcceptanceOutcome(
                CONTRACT_ID, com.escrow.platform.engagement.domain.ContractStatus.CONTRACT_COMPLETED,
                SlotStatus.SLOT_PENDING_SETTLEMENT, null, SETTLEMENT_ID, PAYOUT_ID);
        when(acceptanceTxService.decide(EMPLOYER, CONTRACT_ID, AcceptanceDecision.PASS, null))
                .thenReturn(outcome);
        when(payoutService.dispatch(PAYOUT_ID)).thenReturn(false);

        AcceptanceTxService.AcceptanceOutcome result =
                service.accept(EMPLOYER, CONTRACT_ID, AcceptanceDecision.PASS, null);

        assertThat(result.slotStatus()).isEqualTo(SlotStatus.SLOT_PENDING_SETTLEMENT);
    }

    /** 零实付（无打款单）不触发出站。 */
    @Test
    void acceptShouldNotDispatchWhenPayoutAbsent() {
        AcceptanceTxService.AcceptanceOutcome outcome = new AcceptanceTxService.AcceptanceOutcome(
                CONTRACT_ID, com.escrow.platform.engagement.domain.ContractStatus.CONTRACT_COMPLETED,
                SlotStatus.SLOT_COMPLETED, null, SETTLEMENT_ID, null);
        when(acceptanceTxService.decide(EMPLOYER, CONTRACT_ID, AcceptanceDecision.PASS, null))
                .thenReturn(outcome);

        AcceptanceTxService.AcceptanceOutcome result =
                service.accept(EMPLOYER, CONTRACT_ID, AcceptanceDecision.PASS, null);

        assertThat(result.slotStatus()).isEqualTo(SlotStatus.SLOT_COMPLETED);
        verify(payoutService, never()).dispatch(org.mockito.ArgumentMatchers.anyLong());
    }
}
