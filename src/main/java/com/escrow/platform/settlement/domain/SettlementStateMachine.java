package com.escrow.platform.settlement.domain;

/**
 * 结算单状态机（SRS A.3.4）。
 *
 * <p>V4 落地四条：生成（待生成 → 已确认）、零实付完结（已确认 → 已完结）、
 * 发起打款（已确认 → 打款中）、打款成功（打款中 → 已完结）。
 * 重试与作废归 V5，不提前写空壳。</p>
 *
 * @since V4
 */
public final class SettlementStateMachine {

    private SettlementStateMachine() {
    }

    /** 结算单事件。 */
    public enum Event {

        /** 结算单生成并固化金额 / 费率快照。 */
        CONFIRM,

        /** 零实付：服务费已全部划入平台，直接完结。 */
        ZERO_PAY_COMPLETE,

        /** 已下发打款请求。 */
        START_PAYING,

        /** 打款成功。 */
        SUCCEED,

        /** 打款明确失败，等待下一条尝试。 */
        FAIL_RETRY,

        /** 重试下发。 */
        RETRY_START,

        /** 作废（争议裁定退款等）。 */
        VOID,

        /** 对账冲正：已完结 → 待重试（V6，仅用于通道无记录的错误成功）。 */
        RECON_ROLLBACK
    }

    /**
     * 计算跃迁结果；非法跃迁抛 {@link IllegalStateException}。
     *
     * @param from  当前状态
     * @param event 事件
     * @return 目标状态
     */
    public static SettlementStatus transit(SettlementStatus from, Event event) {
        if (from == null || event == null) {
            throw new IllegalStateException("结算单状态与事件不能为空");
        }
        return switch (event) {
            case CONFIRM -> {
                if (from != SettlementStatus.SETTLEMENT_PENDING_GENERATE) {
                    throw new IllegalStateException("结算单 " + from + " 不能执行 CONFIRM");
                }
                yield SettlementStatus.SETTLEMENT_CONFIRMED;
            }
            case ZERO_PAY_COMPLETE -> {
                if (from != SettlementStatus.SETTLEMENT_CONFIRMED) {
                    throw new IllegalStateException("结算单 " + from + " 不能执行 ZERO_PAY_COMPLETE");
                }
                yield SettlementStatus.SETTLEMENT_COMPLETED;
            }
            case START_PAYING -> {
                if (from != SettlementStatus.SETTLEMENT_CONFIRMED) {
                    throw new IllegalStateException("结算单 " + from + " 不能执行 START_PAYING");
                }
                yield SettlementStatus.SETTLEMENT_PAYING;
            }
            case SUCCEED -> {
                if (from != SettlementStatus.SETTLEMENT_PAYING) {
                    throw new IllegalStateException("结算单 " + from + " 不能执行 SUCCEED");
                }
                yield SettlementStatus.SETTLEMENT_COMPLETED;
            }
            case FAIL_RETRY -> {
                if (from != SettlementStatus.SETTLEMENT_PAYING) {
                    throw new IllegalStateException("结算单 " + from + " 不能执行 FAIL_RETRY");
                }
                yield SettlementStatus.SETTLEMENT_PENDING_RETRY;
            }
            case RETRY_START -> {
                if (from != SettlementStatus.SETTLEMENT_PENDING_RETRY) {
                    throw new IllegalStateException("结算单 " + from + " 不能执行 RETRY_START");
                }
                yield SettlementStatus.SETTLEMENT_PAYING;
            }
            case VOID -> {
                if (from != SettlementStatus.SETTLEMENT_PENDING_GENERATE
                        && from != SettlementStatus.SETTLEMENT_CONFIRMED
                        && from != SettlementStatus.SETTLEMENT_PENDING_RETRY) {
                    throw new IllegalStateException("结算单 " + from + " 不能执行 VOID");
                }
                yield SettlementStatus.SETTLEMENT_VOIDED;
            }
            case RECON_ROLLBACK -> {
                if (from != SettlementStatus.SETTLEMENT_COMPLETED) {
                    throw new IllegalStateException("结算单 " + from + " 不能执行 RECON_ROLLBACK");
                }
                yield SettlementStatus.SETTLEMENT_PENDING_RETRY;
            }
        };
    }
}
