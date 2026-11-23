package com.escrow.platform.task.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 名额状态机测试（v2-c5 / v3-c2 / v3-c3）。
 *
 * <p>锁四条跃迁：待占用 → 已释放（RELEASE）、待占用 → 已占用（OCCUPY）、
 * 已占用 → 履约中（START）、已占用 → 待占用（REOPEN，取消签约）。
 * 取消签约不得走 RELEASE（FR-SIGN.CANCEL.04）。</p>
 *
 * @since V2
 */
class SlotStateMachineTest {

    @Test
    void availableSlotShouldTransitToReleased() {
        assertThat(SlotStateMachine.transit(SlotStatus.SLOT_AVAILABLE, SlotStateMachine.Event.RELEASE))
                .isEqualTo(SlotStatus.SLOT_RELEASED);
    }

    @Test
    void occupiedSlotShouldNotBeReleased() {
        assertThatThrownBy(() -> SlotStateMachine.transit(SlotStatus.SLOT_OCCUPIED,
                SlotStateMachine.Event.RELEASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RELEASE");
    }

    @Test
    void releasedSlotShouldNotBeReleasedAgain() {
        assertThatThrownBy(() -> SlotStateMachine.transit(SlotStatus.SLOT_RELEASED,
                SlotStateMachine.Event.RELEASE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void availableSlotShouldTransitToOccupied() {
        assertThat(SlotStateMachine.transit(SlotStatus.SLOT_AVAILABLE, SlotStateMachine.Event.OCCUPY))
                .isEqualTo(SlotStatus.SLOT_OCCUPIED);
    }

    /** FR-SIGN.START.01：已占用 → 履约中。 */
    @Test
    void occupiedSlotShouldTransitToInProgressOnStart() {
        assertThat(SlotStateMachine.transit(SlotStatus.SLOT_OCCUPIED, SlotStateMachine.Event.START))
                .isEqualTo(SlotStatus.SLOT_IN_PROGRESS);
    }

    /** FR-SIGN.CANCEL.01 / CANCEL.04：取消签约回到待占用，而不是已释放。 */
    @Test
    void occupiedSlotShouldReopenToAvailableOnCancel() {
        assertThat(SlotStateMachine.transit(SlotStatus.SLOT_OCCUPIED, SlotStateMachine.Event.REOPEN))
                .isEqualTo(SlotStatus.SLOT_AVAILABLE);
    }

    /** FR-SIGN.CANCEL.03：履约中不能单方取消，因此不能再 REOPEN；也不能重复 START。 */
    @Test
    void inProgressSlotShouldNotStartOrReopen() {
        for (SlotStateMachine.Event event : new SlotStateMachine.Event[]{
                SlotStateMachine.Event.START, SlotStateMachine.Event.REOPEN}) {
            assertThatThrownBy(() -> SlotStateMachine.transit(SlotStatus.SLOT_IN_PROGRESS, event))
                    .as("履约中 不能执行 %s", event)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** FR-DELIVER.SUBMIT.02：履约中 → 已交付。 */
    @Test
    void inProgressSlotShouldTransitToDelivered() {
        assertThat(SlotStateMachine.transit(SlotStatus.SLOT_IN_PROGRESS, SlotStateMachine.Event.DELIVER))
                .isEqualTo(SlotStatus.SLOT_DELIVERED);
    }

    /** 非「履约中」不能交付：未开始没有履约过程，已交付不能重复交付。 */
    @Test
    void nonInProgressSlotsShouldNotDeliver() {
        for (SlotStatus from : new SlotStatus[]{
                SlotStatus.SLOT_AVAILABLE, SlotStatus.SLOT_OCCUPIED,
                SlotStatus.SLOT_DELIVERED, SlotStatus.SLOT_RELEASED}) {
            assertThatThrownBy(() -> SlotStateMachine.transit(from, SlotStateMachine.Event.DELIVER))
                    .as("%s 不能执行 DELIVER", from)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** 待占用不能 START / REOPEN；已释放是终态。 */
    @Test
    void availableAndReleasedSlotsShouldRejectStartAndReopen() {
        for (SlotStatus from : new SlotStatus[]{
                SlotStatus.SLOT_AVAILABLE, SlotStatus.SLOT_RELEASED}) {
            for (SlotStateMachine.Event event : new SlotStateMachine.Event[]{
                    SlotStateMachine.Event.START, SlotStateMachine.Event.REOPEN}) {
                assertThatThrownBy(() -> SlotStateMachine.transit(from, event))
                        .as("%s 不能执行 %s", from, event)
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    /** FR-ACCEPT.RESULT.01：验收通过且实付 &gt; 0 → 待结算。 */
    @Test
    void deliveredShouldBecomePendingSettlementOnAccept() {
        assertThat(SlotStateMachine.transit(SlotStatus.SLOT_DELIVERED, SlotStateMachine.Event.ACCEPT))
                .isEqualTo(SlotStatus.SLOT_PENDING_SETTLEMENT);
    }

    /** FR-ACCEPT.RESULT.02：拒收 → 待裁决。 */
    @Test
    void deliveredShouldBecomeArbitrationOnReject() {
        assertThat(SlotStateMachine.transit(SlotStatus.SLOT_DELIVERED, SlotStateMachine.Event.REJECT))
                .isEqualTo(SlotStatus.SLOT_PENDING_ARBITRATION);
    }

    /** FR-SETTLE.FEE.03：零实付直接完结。 */
    @Test
    void deliveredShouldCompleteOnZeroPay() {
        assertThat(SlotStateMachine.transit(SlotStatus.SLOT_DELIVERED,
                SlotStateMachine.Event.ZERO_PAY_COMPLETE))
                .isEqualTo(SlotStatus.SLOT_COMPLETED);
    }

    /** FR-PAY.CONFIRM.01：打款成功，待结算 → 已完结。 */
    @Test
    void pendingSettlementShouldCompleteOnPayoutSuccess() {
        assertThat(SlotStateMachine.transit(SlotStatus.SLOT_PENDING_SETTLEMENT,
                SlotStateMachine.Event.SETTLEMENT_SUCCEED))
                .isEqualTo(SlotStatus.SLOT_COMPLETED);
    }

    /** 打款失败额度用尽：待结算 → 待裁决，而不是回到已交付。 */
    @Test
    void pendingSettlementShouldBecomeArbitrationOnPayoutFailure() {
        assertThat(SlotStateMachine.transit(SlotStatus.SLOT_PENDING_SETTLEMENT,
                SlotStateMachine.Event.SETTLEMENT_FAIL))
                .isEqualTo(SlotStatus.SLOT_PENDING_ARBITRATION);
    }

    /** 验收相关事件只能从「已交付」出发。 */
    @Test
    void nonDeliveredSlotsShouldNotAcceptRejectOrZeroPay() {
        for (SlotStatus from : new SlotStatus[]{
                SlotStatus.SLOT_AVAILABLE, SlotStatus.SLOT_OCCUPIED,
                SlotStatus.SLOT_IN_PROGRESS, SlotStatus.SLOT_PENDING_SETTLEMENT}) {
            for (SlotStateMachine.Event event : new SlotStateMachine.Event[]{
                    SlotStateMachine.Event.ACCEPT, SlotStateMachine.Event.REJECT,
                    SlotStateMachine.Event.ZERO_PAY_COMPLETE}) {
                assertThatThrownBy(() -> SlotStateMachine.transit(from, event))
                        .as("%s 不能执行 %s", from, event)
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    /** v6-c2：对账冲正回退，已完结 → 待结算，等待重新出站。 */
    @Test
    void completedShouldReopenOnRecon() {
        assertThat(SlotStateMachine.transit(SlotStatus.SLOT_COMPLETED,
                SlotStateMachine.Event.RECON_ROLLBACK))
                .isEqualTo(SlotStatus.SLOT_PENDING_SETTLEMENT);
    }

    @Test
    void nullStatusShouldBeRejected() {
        assertThatThrownBy(() -> SlotStateMachine.transit(null, SlotStateMachine.Event.RELEASE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullEventShouldBeRejected() {
        assertThatThrownBy(() -> SlotStateMachine.transit(SlotStatus.SLOT_OCCUPIED, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
