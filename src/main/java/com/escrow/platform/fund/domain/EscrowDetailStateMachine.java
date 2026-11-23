package com.escrow.platform.fund.domain;

/**
 * 托管明细状态机（SRS A.3.3）。
 *
 * <p>V2 落地退款：{@code ESCROW_DETAIL_HELD -> ESCROW_DETAIL_REFUNDED}；
 * V4 追加验收 / 零实付 / 打款成功 / 争议冻结四条：
 * {@code HELD -> RELEASING}、{@code HELD -> RELEASED}、{@code RELEASING -> RELEASED}、
 * {@code HELD -> FROZEN}。已释放 / 已退款是终态，再次资金操作必须被拒绝（FR-REFUND.GUARD.01）。</p>
 *
 * <p>非法跃迁抛 {@link IllegalStateException}：调用方必须先用条件更新抢占状态，
 * 抢不到就不该调用本方法；走到这里说明守卫写漏了。</p>
 *
 * @since V2
 */
public final class EscrowDetailStateMachine {

    /** 托管明细跃迁事件。 */
    public enum Event {

        /** 撤单 / 到期退款。 */
        REFUND,

        /** 验收通过且实付 &gt; 0：冻结额转入在途（FR-ESCROW.RELEASE.01）。 */
        ACCEPT,

        /** 验收通过且实付 = 0：冻结额直接转平台收入（FR-SETTLE.FEE.03）。 */
        ZERO_PAY,

        /** 打款成功：在途资金结清，明细释放（FR-PAY.CONFIRM.01）。 */
        SETTLE_SUCCESS,

        /** 雇主拒收：冻结额继续冻结，等待裁决（FR-ACCEPT.RESULT.02）。 */
        DISPUTE,

        /** 争议撤回 / 裁决放款前置：冻结 → 已托管（V6，FR-DISPUTE.WITHDRAW.01）。 */
        DISPUTE_UNFREEZE,

        /** 裁决退款：冻结 → 已退款，钱退回雇主（V6，FR-DISPUTE.RESOLVE.01）。 */
        DISPUTE_REFUND
    }

    private EscrowDetailStateMachine() {
    }

    /**
     * 计算跃迁后的状态。
     *
     * @param from  当前状态
     * @param event 事件
     * @return 跃迁后的状态
     * @throws IllegalStateException 状态为空或跃迁非法
     */
    public static EscrowDetailStatus transit(EscrowDetailStatus from, Event event) {
        if (from == null || event == null) {
            throw new IllegalStateException("托管明细状态跃迁的参数不能为空");
        }
        return switch (event) {
            case REFUND -> {
                if (from != EscrowDetailStatus.ESCROW_DETAIL_HELD) {
                    throw new IllegalStateException("托管明细 " + from + " 不能执行 REFUND");
                }
                yield EscrowDetailStatus.ESCROW_DETAIL_REFUNDED;
            }
            case ACCEPT -> {
                if (from != EscrowDetailStatus.ESCROW_DETAIL_HELD) {
                    throw new IllegalStateException("托管明细 " + from + " 不能执行 ACCEPT");
                }
                yield EscrowDetailStatus.ESCROW_DETAIL_RELEASING;
            }
            case ZERO_PAY -> {
                if (from != EscrowDetailStatus.ESCROW_DETAIL_HELD) {
                    throw new IllegalStateException("托管明细 " + from + " 不能执行 ZERO_PAY");
                }
                yield EscrowDetailStatus.ESCROW_DETAIL_RELEASED;
            }
            case SETTLE_SUCCESS -> {
                if (from != EscrowDetailStatus.ESCROW_DETAIL_RELEASING) {
                    throw new IllegalStateException("托管明细 " + from + " 不能执行 SETTLE_SUCCESS");
                }
                yield EscrowDetailStatus.ESCROW_DETAIL_RELEASED;
            }
            case DISPUTE -> {
                if (from != EscrowDetailStatus.ESCROW_DETAIL_HELD) {
                    throw new IllegalStateException("托管明细 " + from + " 不能执行 DISPUTE");
                }
                yield EscrowDetailStatus.ESCROW_DETAIL_FROZEN;
            }
            case DISPUTE_UNFREEZE -> {
                if (from != EscrowDetailStatus.ESCROW_DETAIL_FROZEN) {
                    throw new IllegalStateException("托管明细 " + from + " 不能执行 DISPUTE_UNFREEZE");
                }
                yield EscrowDetailStatus.ESCROW_DETAIL_HELD;
            }
            case DISPUTE_REFUND -> {
                if (from != EscrowDetailStatus.ESCROW_DETAIL_FROZEN) {
                    throw new IllegalStateException("托管明细 " + from + " 不能执行 DISPUTE_REFUND");
                }
                yield EscrowDetailStatus.ESCROW_DETAIL_REFUNDED;
            }
        };
    }
}
