package com.escrow.platform.settlement.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结算单状态机测试（SRS A.3.4，v4-c2）。
 *
 * @since V4
 */
class SettlementStateMachineTest {

    /** FR-SETTLE.GEN.01：待生成 → 已确认。 */
    @Test
    void pendingGenerateShouldConfirm() {
        assertThat(SettlementStateMachine.transit(SettlementStatus.SETTLEMENT_PENDING_GENERATE,
                SettlementStateMachine.Event.CONFIRM))
                .isEqualTo(SettlementStatus.SETTLEMENT_CONFIRMED);
    }

    /** FR-SETTLE.FEE.03：零实付直接完结。 */
    @Test
    void confirmedShouldCompleteOnZeroPay() {
        assertThat(SettlementStateMachine.transit(SettlementStatus.SETTLEMENT_CONFIRMED,
                SettlementStateMachine.Event.ZERO_PAY_COMPLETE))
                .isEqualTo(SettlementStatus.SETTLEMENT_COMPLETED);
    }

    /** FR-PAY.INTENT.01：已确认 → 打款中。 */
    @Test
    void confirmedShouldStartPaying() {
        assertThat(SettlementStateMachine.transit(SettlementStatus.SETTLEMENT_CONFIRMED,
                SettlementStateMachine.Event.START_PAYING))
                .isEqualTo(SettlementStatus.SETTLEMENT_PAYING);
    }

    /** FR-PAY.CONFIRM.01：打款中 → 已完结。 */
    @Test
    void payingShouldCompleteOnSuccess() {
        assertThat(SettlementStateMachine.transit(SettlementStatus.SETTLEMENT_PAYING,
                SettlementStateMachine.Event.SUCCEED))
                .isEqualTo(SettlementStatus.SETTLEMENT_COMPLETED);
    }

    /** 打款失败：打款中 → 待重试（V4 只落状态，重试任务在 V5）。 */
    @Test
    void payingShouldMoveToPendingRetryOnFailure() {
        assertThat(SettlementStateMachine.transit(SettlementStatus.SETTLEMENT_PAYING,
                SettlementStateMachine.Event.FAIL_RETRY))
                .isEqualTo(SettlementStatus.SETTLEMENT_PENDING_RETRY);
    }

    /** 已完结只允许对账回退，其余事件一律拒绝。 */
    @Test
    void completedShouldRejectAllEvents() {
        for (SettlementStateMachine.Event event : SettlementStateMachine.Event.values()) {
            if (event == SettlementStateMachine.Event.RECON_ROLLBACK) {
                continue;
            }
            assertThatThrownBy(() -> SettlementStateMachine.transit(
                    SettlementStatus.SETTLEMENT_COMPLETED, event))
                    .as("SETTLEMENT_COMPLETED 不能执行 %s", event)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** v6-c2：对账冲正回退，已完结 → 待重试。 */
    @Test
    void completedShouldRollbackOnRecon() {
        assertThat(SettlementStateMachine.transit(SettlementStatus.SETTLEMENT_COMPLETED,
                SettlementStateMachine.Event.RECON_ROLLBACK))
                .isEqualTo(SettlementStatus.SETTLEMENT_PENDING_RETRY);
    }

    @Test
    void nullArgumentsShouldThrow() {
        assertThatThrownBy(() -> SettlementStateMachine.transit(null,
                SettlementStateMachine.Event.CONFIRM))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SettlementStateMachine.transit(
                SettlementStatus.SETTLEMENT_CONFIRMED, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
