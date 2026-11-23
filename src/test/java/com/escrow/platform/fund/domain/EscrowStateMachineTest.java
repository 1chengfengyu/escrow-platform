package com.escrow.platform.fund.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 托管明细 / 托管单状态机测试（v2-c5）。
 *
 * @since V2
 */
class EscrowStateMachineTest {

    @Test
    void heldDetailShouldTransitToRefunded() {
        assertThat(EscrowDetailStateMachine.transit(EscrowDetailStatus.ESCROW_DETAIL_HELD,
                EscrowDetailStateMachine.Event.REFUND))
                .isEqualTo(EscrowDetailStatus.ESCROW_DETAIL_REFUNDED);
    }

    @Test
    void refundedDetailShouldNotBeRefundedAgain() {
        assertThatThrownBy(() -> EscrowDetailStateMachine.transit(
                EscrowDetailStatus.ESCROW_DETAIL_REFUNDED, EscrowDetailStateMachine.Event.REFUND))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REFUND");
    }

    @Test
    void releasedDetailShouldNotBeRefunded() {
        assertThatThrownBy(() -> EscrowDetailStateMachine.transit(
                EscrowDetailStatus.ESCROW_DETAIL_RELEASED, EscrowDetailStateMachine.Event.REFUND))
                .isInstanceOf(IllegalStateException.class);
    }

    /** FR-ESCROW.RELEASE.01：验收通过，已托管 → 释放中。 */
    @Test
    void heldDetailShouldTransitToReleasingOnAccept() {
        assertThat(EscrowDetailStateMachine.transit(EscrowDetailStatus.ESCROW_DETAIL_HELD,
                EscrowDetailStateMachine.Event.ACCEPT))
                .isEqualTo(EscrowDetailStatus.ESCROW_DETAIL_RELEASING);
    }

    /** FR-PAY.CONFIRM.01：打款成功，释放中 → 已释放。 */
    @Test
    void releasingDetailShouldTransitToReleasedOnSuccess() {
        assertThat(EscrowDetailStateMachine.transit(EscrowDetailStatus.ESCROW_DETAIL_RELEASING,
                EscrowDetailStateMachine.Event.SETTLE_SUCCESS))
                .isEqualTo(EscrowDetailStatus.ESCROW_DETAIL_RELEASED);
    }

    /** FR-SETTLE.FEE.03：零实付，已托管 → 已释放。 */
    @Test
    void heldDetailShouldTransitToReleasedOnZeroPay() {
        assertThat(EscrowDetailStateMachine.transit(EscrowDetailStatus.ESCROW_DETAIL_HELD,
                EscrowDetailStateMachine.Event.ZERO_PAY))
                .isEqualTo(EscrowDetailStatus.ESCROW_DETAIL_RELEASED);
    }

    /** FR-ACCEPT.RESULT.02：拒收，已托管 → 冻结。 */
    @Test
    void heldDetailShouldTransitToFrozenOnDispute() {
        assertThat(EscrowDetailStateMachine.transit(EscrowDetailStatus.ESCROW_DETAIL_HELD,
                EscrowDetailStateMachine.Event.DISPUTE))
                .isEqualTo(EscrowDetailStatus.ESCROW_DETAIL_FROZEN);
    }

    /** 已释放是终态：不能再释放 / 冻结。 */
    @Test
    void releasedDetailShouldRejectReleaseAndDispute() {
        for (EscrowDetailStateMachine.Event event : new EscrowDetailStateMachine.Event[]{
                EscrowDetailStateMachine.Event.ACCEPT, EscrowDetailStateMachine.Event.ZERO_PAY,
                EscrowDetailStateMachine.Event.SETTLE_SUCCESS, EscrowDetailStateMachine.Event.DISPUTE}) {
            assertThatThrownBy(() -> EscrowDetailStateMachine.transit(
                    EscrowDetailStatus.ESCROW_DETAIL_RELEASED, event))
                    .as("已释放 不能执行 %s", event)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void heldOrderShouldTransitToClosed() {
        assertThat(EscrowOrderStateMachine.transit(EscrowOrderStatus.ESCROW_ORDER_HELD,
                EscrowOrderStateMachine.Event.CLOSE))
                .isEqualTo(EscrowOrderStatus.ESCROW_ORDER_CLOSED);
    }

    @Test
    void closedOrderShouldNotBeClosedAgain() {
        assertThatThrownBy(() -> EscrowOrderStateMachine.transit(EscrowOrderStatus.ESCROW_ORDER_CLOSED,
                EscrowOrderStateMachine.Event.CLOSE))
                .isInstanceOf(IllegalStateException.class);
    }
}
