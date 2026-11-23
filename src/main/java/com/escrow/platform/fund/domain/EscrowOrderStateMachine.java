package com.escrow.platform.fund.domain;

/**
 * 托管单状态机（SRS 3.4.2）。
 *
 * <p>V2 只有一条跃迁：全部明细终态后 {@code ESCROW_ORDER_HELD -> ESCROW_ORDER_CLOSED}。
 * 托管单状态由明细集合派生，不允许单独推进。</p>
 *
 * @since V2
 */
public final class EscrowOrderStateMachine {

    /** 托管单跃迁事件。 */
    public enum Event {

        /** 全部明细进入终态后关闭托管单。 */
        CLOSE
    }

    private EscrowOrderStateMachine() {
    }

    /**
     * 计算跃迁后的状态。
     *
     * @param from  当前状态
     * @param event 事件
     * @return 跃迁后的状态
     * @throws IllegalStateException 状态为空或跃迁非法
     */
    public static EscrowOrderStatus transit(EscrowOrderStatus from, Event event) {
        if (from == null || event == null) {
            throw new IllegalStateException("托管单状态跃迁的参数不能为空");
        }
        return switch (event) {
            case CLOSE -> {
                if (from != EscrowOrderStatus.ESCROW_ORDER_HELD) {
                    throw new IllegalStateException("托管单 " + from + " 不能执行 CLOSE");
                }
                yield EscrowOrderStatus.ESCROW_ORDER_CLOSED;
            }
        };
    }
}
