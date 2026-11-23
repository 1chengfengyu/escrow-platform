package com.escrow.platform.settlement.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 打款单状态机测试（SRS A.3.5，v4-c4）。
 *
 * @since V4
 */
class PayoutStateMachineTest {

    /** 先落库再出站：待执行 → 执行中。 */
    @Test
    void pendingShouldDispatch() {
        assertThat(PayoutStateMachine.transit(PayoutStatus.PAYOUT_PENDING_EXECUTE,
                PayoutStateMachine.Event.DISPATCH))
                .isEqualTo(PayoutStatus.PAYOUT_EXECUTING);
    }

    /** FR-PAY.CONFIRM.01：执行中 → 成功。 */
    @Test
    void executingShouldSucceed() {
        assertThat(PayoutStateMachine.transit(PayoutStatus.PAYOUT_EXECUTING,
                PayoutStateMachine.Event.SUCCEED))
                .isEqualTo(PayoutStatus.PAYOUT_SUCCESS);
    }

    /** 明确失败且有额度：执行中 → 失败。 */
    @Test
    void executingShouldFailWhenRetryable() {
        assertThat(PayoutStateMachine.transit(PayoutStatus.PAYOUT_EXECUTING,
                PayoutStateMachine.Event.FAIL))
                .isEqualTo(PayoutStatus.PAYOUT_FAIL);
    }

    /** 明确失败且额度用尽：执行中 → 待人工。 */
    @Test
    void executingShouldRequireManualReviewWhenLimitReached() {
        assertThat(PayoutStateMachine.transit(PayoutStatus.PAYOUT_EXECUTING,
                PayoutStateMachine.Event.FAIL_LIMIT))
                .isEqualTo(PayoutStatus.PAYOUT_MANUAL_REVIEW);
    }

    /** 超时 / 应答丢失：执行中 → 待查证，不是失败。 */
    @Test
    void executingShouldBecomeUnknownOnTimeout() {
        assertThat(PayoutStateMachine.transit(PayoutStatus.PAYOUT_EXECUTING,
                PayoutStateMachine.Event.UNKNOWN))
                .isEqualTo(PayoutStatus.PAYOUT_PENDING_VERIFICATION);
    }

    /** 成功是终态：除对账冲正外，任何事件都不能再推进。 */
    @Test
    void successShouldRejectAllEvents() {
        for (PayoutStateMachine.Event event : PayoutStateMachine.Event.values()) {
            if (event == PayoutStateMachine.Event.REVERSE) {
                continue;
            }
            assertThatThrownBy(() -> PayoutStateMachine.transit(PayoutStatus.PAYOUT_SUCCESS, event))
                    .as("PAYOUT_SUCCESS 不能执行 %s", event)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** v6-c2：对账冲正，成功 → 失败，由结算单回退重试。 */
    @Test
    void successShouldReverseOnRecon() {
        assertThat(PayoutStateMachine.transit(PayoutStatus.PAYOUT_SUCCESS,
                PayoutStateMachine.Event.REVERSE))
                .isEqualTo(PayoutStatus.PAYOUT_FAIL);
    }

    /** 待查证不能再出站（必须先查证，禁止重发）。 */
    @Test
    void pendingVerificationShouldRejectDispatch() {
        assertThatThrownBy(() -> PayoutStateMachine.transit(
                PayoutStatus.PAYOUT_PENDING_VERIFICATION, PayoutStateMachine.Event.DISPATCH))
                .isInstanceOf(IllegalStateException.class);
    }

    /** v5-c1：成功但金额不一致 → 待人工，不自动完账。 */
    @Test
    void amountMismatchShouldRequireManualReview() {
        assertThat(PayoutStateMachine.transit(PayoutStatus.PAYOUT_EXECUTING,
                PayoutStateMachine.Event.AMOUNT_MISMATCH))
                .isEqualTo(PayoutStatus.PAYOUT_MANUAL_REVIEW);
    }

    /** v5-c2：查证确认成功 → 成功，唯一从「待查证」到成功的一条边。 */
    @Test
    void verifiedSuccessShouldSucceed() {
        assertThat(PayoutStateMachine.transit(PayoutStatus.PAYOUT_PENDING_VERIFICATION,
                PayoutStateMachine.Event.VERIFY_SUCCESS))
                .isEqualTo(PayoutStatus.PAYOUT_SUCCESS);
    }

    /** v5-c2：查证判失败且有额度 → 失败，允许新尝试。 */
    @Test
    void verifiedFailShouldFail() {
        assertThat(PayoutStateMachine.transit(PayoutStatus.PAYOUT_PENDING_VERIFICATION,
                PayoutStateMachine.Event.VERIFY_FAIL))
                .isEqualTo(PayoutStatus.PAYOUT_FAIL);
    }

    /** v5-c2：查证判失败但额度用尽 → 待人工。 */
    @Test
    void verifiedFailAtLimitShouldRequireManualReview() {
        assertThat(PayoutStateMachine.transit(PayoutStatus.PAYOUT_PENDING_VERIFICATION,
                PayoutStateMachine.Event.VERIFY_LIMIT))
                .isEqualTo(PayoutStatus.PAYOUT_MANUAL_REVIEW);
    }

    /** v5-c4：人工确认实际金额 → 成功。 */
    @Test
    void manualConfirmShouldSucceed() {
        assertThat(PayoutStateMachine.transit(PayoutStatus.PAYOUT_MANUAL_REVIEW,
                PayoutStateMachine.Event.MANUAL_CONFIRM))
                .isEqualTo(PayoutStatus.PAYOUT_SUCCESS);
    }

    /** 待人工不得再次出站（必须人工处置）。 */
    @Test
    void manualReviewShouldRejectDispatch() {
        assertThatThrownBy(() -> PayoutStateMachine.transit(
                PayoutStatus.PAYOUT_MANUAL_REVIEW, PayoutStateMachine.Event.DISPATCH))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 查证事件不能作用在执行中上：查证只针对未知态。 */
    @Test
    void verificationEventsShouldRejectExecuting() {
        assertThatThrownBy(() -> PayoutStateMachine.transit(PayoutStatus.PAYOUT_EXECUTING,
                PayoutStateMachine.Event.VERIFY_SUCCESS))
                .isInstanceOf(IllegalStateException.class);
    }
}
