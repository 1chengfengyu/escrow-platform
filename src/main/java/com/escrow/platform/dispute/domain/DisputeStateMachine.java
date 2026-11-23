package com.escrow.platform.dispute.domain;

/**
 * 争议状态机（SRS A.3.4「争议状态机」）。
 *
 * <p>四条跃迁：拒收 / 申诉开启（{@code DISPUTE_PENDING} 由 V4 直接插入）、
 * 裁决前置撤回（{@code PENDING -> WITHDRAWN}）、裁决（{@code PENDING -> RESOLVED}）、
 * 撤回后再次发起复用同一张单（{@code WITHDRAWN -> PENDING}，V6，FR-DISPUTE.WITHDRAW.02）。</p>
 *
 * <p>非法跃迁抛 {@link IllegalStateException}：调用方必须先做条件更新抢占，抢不到就不该调用。</p>
 *
 * @since V6
 */
public final class DisputeStateMachine {

    /** 争议状态跃迁事件。 */
    public enum Event {

        /** 发起方在裁决前撤回（FR-DISPUTE.WITHDRAW.01）。 */
        WITHDRAW,

        /** 管理员裁决放款或退款（FR-DISPUTE.RESOLVE.01）。 */
        RESOLVE,

        /** 撤回后再次发起争议，复用同一张单（FR-DISPUTE.WITHDRAW.02）。 */
        REOPEN
    }

    private DisputeStateMachine() {
    }

    /**
     * 计算跃迁后的状态。
     *
     * @param from  当前状态
     * @param event 事件
     * @return 跃迁后的状态
     * @throws IllegalStateException 参数为空或跃迁非法
     */
    public static DisputeStatus transit(DisputeStatus from, Event event) {
        if (from == null || event == null) {
            throw new IllegalStateException("争议状态跃迁的参数不能为空");
        }
        return switch (event) {
            case WITHDRAW -> {
                if (from != DisputeStatus.DISPUTE_PENDING) {
                    throw new IllegalStateException("争议 " + from + " 不能执行 WITHDRAW");
                }
                yield DisputeStatus.DISPUTE_WITHDRAWN;
            }
            case RESOLVE -> {
                if (from != DisputeStatus.DISPUTE_PENDING) {
                    throw new IllegalStateException("争议 " + from + " 不能执行 RESOLVE");
                }
                yield DisputeStatus.DISPUTE_RESOLVED;
            }
            case REOPEN -> {
                if (from != DisputeStatus.DISPUTE_WITHDRAWN) {
                    throw new IllegalStateException("争议 " + from + " 不能执行 REOPEN");
                }
                yield DisputeStatus.DISPUTE_PENDING;
            }
        };
    }
}
