package com.escrow.platform.dispute.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 争议状态机测试（V6，FR-DISPUTE.WITHDRAW.01 / WITHDRAW.02 / RESOLVE.01）。
 *
 * @since V6
 */
class DisputeStateMachineTest {

    /** 待裁决可撤回。 */
    @Test
    void pendingWithdrawShouldGoWithdrawn() {
        assertThat(DisputeStateMachine.transit(DisputeStatus.DISPUTE_PENDING,
                DisputeStateMachine.Event.WITHDRAW)).isEqualTo(DisputeStatus.DISPUTE_WITHDRAWN);
    }

    /** 待裁决可裁决。 */
    @Test
    void pendingResolveShouldGoResolved() {
        assertThat(DisputeStateMachine.transit(DisputeStatus.DISPUTE_PENDING,
                DisputeStateMachine.Event.RESOLVE)).isEqualTo(DisputeStatus.DISPUTE_RESOLVED);
    }

    /** 撤回后可再次发起，复用同一张单。 */
    @Test
    void withdrawnReopenShouldGoPending() {
        assertThat(DisputeStateMachine.transit(DisputeStatus.DISPUTE_WITHDRAWN,
                DisputeStateMachine.Event.REOPEN)).isEqualTo(DisputeStatus.DISPUTE_PENDING);
    }

    /** 已裁决不可撤回。 */
    @Test
    void resolvedWithdrawShouldFail() {
        assertThatThrownBy(() -> DisputeStateMachine.transit(DisputeStatus.DISPUTE_RESOLVED,
                DisputeStateMachine.Event.WITHDRAW))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 已撤回不可直接裁决。 */
    @Test
    void withdrawnResolveShouldFail() {
        assertThatThrownBy(() -> DisputeStateMachine.transit(DisputeStatus.DISPUTE_WITHDRAWN,
                DisputeStateMachine.Event.RESOLVE))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 参数为空直接拒绝。 */
    @Test
    void nullArgumentsShouldFail() {
        assertThatThrownBy(() -> DisputeStateMachine.transit(null,
                DisputeStateMachine.Event.WITHDRAW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DisputeStateMachine.transit(DisputeStatus.DISPUTE_PENDING, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
