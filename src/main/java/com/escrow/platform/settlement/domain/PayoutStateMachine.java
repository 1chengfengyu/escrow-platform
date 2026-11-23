package com.escrow.platform.settlement.domain;

/**
 * 打款单状态机（SRS A.3.5）。
 *
 * <p>V4 落地五条：待执行 → 执行中（T2 前落库）、执行中 → 成功 / 失败 / 待人工 / 未知。
 * V5 补齐从「未知」与「待人工」出发的四条出口：查证成功、查证判失败、查证超限、
 * 金额不一致转人工，以及人工确认成功。已进入成功 / 失败的终态不再有出口。</p>
 *
 * @since V4
 */
public final class PayoutStateMachine {

    private PayoutStateMachine() {
    }

    /** 打款单事件。 */
    public enum Event {

        /** 已抢到执行权，落「执行中」后再出站。 */
        DISPATCH,

        /** 通道明确成功且金额一致。 */
        SUCCEED,

        /** 通道明确失败且仍有尝试额度。 */
        FAIL,

        /** 通道明确失败且额度用尽，转人工。 */
        FAIL_LIMIT,

        /** 超时 / 应答丢失，进入未知待查证。 */
        UNKNOWN,

        /** 通道成功但实际金额与请求不一致，转人工。 */
        AMOUNT_MISMATCH,

        /** 查证确认成功（金额一致）。 */
        VERIFY_SUCCESS,

        /** 查证确认失败 / 通道无记录，仍有额度。 */
        VERIFY_FAIL,

        /** 查证判失败但额度用尽 / 查证金额不一致，转人工。 */
        VERIFY_LIMIT,

        /** 人工确认实际金额后按成功落账。 */
        MANUAL_CONFIRM,

        /** 对账冲正：本地成功但通道无记录，回退为失败以便重新出站（V6）。 */
        REVERSE
    }

    /**
     * 计算跃迁结果；非法跃迁抛 {@link IllegalStateException}。
     *
     * @param from  当前状态
     * @param event 事件
     * @return 目标状态
     */
    public static PayoutStatus transit(PayoutStatus from, Event event) {
        if (from == null || event == null) {
            throw new IllegalStateException("打款单状态与事件不能为空");
        }
        return switch (event) {
            case DISPATCH -> require(from, PayoutStatus.PAYOUT_PENDING_EXECUTE, event,
                    PayoutStatus.PAYOUT_EXECUTING);
            case SUCCEED -> require(from, PayoutStatus.PAYOUT_EXECUTING, event,
                    PayoutStatus.PAYOUT_SUCCESS);
            case FAIL -> require(from, PayoutStatus.PAYOUT_EXECUTING, event,
                    PayoutStatus.PAYOUT_FAIL);
            case FAIL_LIMIT -> require(from, PayoutStatus.PAYOUT_EXECUTING, event,
                    PayoutStatus.PAYOUT_MANUAL_REVIEW);
            case UNKNOWN -> require(from, PayoutStatus.PAYOUT_EXECUTING, event,
                    PayoutStatus.PAYOUT_PENDING_VERIFICATION);
            case AMOUNT_MISMATCH -> require(from, PayoutStatus.PAYOUT_EXECUTING, event,
                    PayoutStatus.PAYOUT_MANUAL_REVIEW);
            case VERIFY_SUCCESS -> require(from, PayoutStatus.PAYOUT_PENDING_VERIFICATION, event,
                    PayoutStatus.PAYOUT_SUCCESS);
            case VERIFY_FAIL -> require(from, PayoutStatus.PAYOUT_PENDING_VERIFICATION, event,
                    PayoutStatus.PAYOUT_FAIL);
            case VERIFY_LIMIT -> require(from, PayoutStatus.PAYOUT_PENDING_VERIFICATION, event,
                    PayoutStatus.PAYOUT_MANUAL_REVIEW);
            case MANUAL_CONFIRM -> require(from, PayoutStatus.PAYOUT_MANUAL_REVIEW, event,
                    PayoutStatus.PAYOUT_SUCCESS);
            case REVERSE -> require(from, PayoutStatus.PAYOUT_SUCCESS, event,
                    PayoutStatus.PAYOUT_FAIL);
        };
    }

    private static PayoutStatus require(PayoutStatus from, PayoutStatus expected, Event event,
                                        PayoutStatus target) {
        if (from != expected) {
            throw new IllegalStateException("打款单 " + from + " 不能执行 " + event);
        }
        return target;
    }
}
