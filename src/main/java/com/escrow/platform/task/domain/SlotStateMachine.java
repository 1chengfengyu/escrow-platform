package com.escrow.platform.task.domain;

/**
 * 名额状态机（SRS A.3.1）。
 *
 * <p>已落地十条跃迁：撤单 / 到期的 {@code SLOT_AVAILABLE -> SLOT_RELEASED}（V2）、
 * 录用的 {@code SLOT_AVAILABLE -> SLOT_OCCUPIED}（V3-c2）、开始履约的
 * {@code SLOT_OCCUPIED -> SLOT_IN_PROGRESS}、取消签约的 {@code SLOT_OCCUPIED -> SLOT_AVAILABLE}
 * （V3-c3）、交付的 {@code SLOT_IN_PROGRESS -> SLOT_DELIVERED}（V3-c4）、
 * 验收通过 / 拒收 / 零实付 / 打款结果（V4）。</p>
 *
 * <p><b>取消签约不得用 {@code RELEASE}</b>：{@code SLOT_RELEASED} 表示「此名额不再补员」，
 * 只由撤单 / 到期产生（FR-SIGN.CANCEL.04）；取消签约回到 {@code SLOT_AVAILABLE}，
 * 名额重新开放（FR-SIGN.CANCEL.01 / CANCEL.02）。</p>
 *
 * <p>非法跃迁抛 {@link IllegalStateException}：这是服务层逻辑错误（守卫写漏），
 * 不是用户可纠正的输入错误，不能转成 4xx 业务码。</p>
 *
 * @since V2
 */
public final class SlotStateMachine {

    /** 名额状态跃迁事件。 */
    public enum Event {

        /** 撤单 / 到期释放待占用名额。 */
        RELEASE,

        /** 录用成功占用待占用名额（FR-SIGN.HIRE.01）。 */
        OCCUPY,

        /** 劳动者开始履约（FR-SIGN.START.01）。 */
        START,

        /** 取消签约（劳动者主动 / 超时自动），名额重新开放（FR-SIGN.CANCEL.01 / START.02）。 */
        REOPEN,

        /** 劳动者提交交付物（FR-DELIVER.SUBMIT.02）。 */
        DELIVER,

        /** 验收通过且实付 &gt; 0：名额进入待结算（FR-ACCEPT.RESULT.01）。 */
        ACCEPT,

        /** 雇主拒收：名额进入待裁决并触发争议冻结（FR-ACCEPT.RESULT.02）。 */
        REJECT,

        /** 验收通过且实付 = 0：零实付直接完结（FR-SETTLE.FEE.03）。 */
        ZERO_PAY_COMPLETE,

        /** 打款成功：待结算 → 已完结（FR-PAY.CONFIRM.01）。 */
        SETTLEMENT_SUCCEED,

        /** 打款失败且额度用尽 / 人工标记失败：待结算 → 待裁决。 */
        SETTLEMENT_FAIL,

        /** 对账冲正：已完结 → 待结算，等待重新出站（V6）。 */
        RECON_ROLLBACK,

        /** 争议撤回：待裁决 → 已交付，重新进入验收（V6，FR-DISPUTE.WITHDRAW.01）。 */
        ARBITRATION_WITHDRAW,

        /** 裁决放款：待裁决 → 待结算（V6，FR-DISPUTE.RESOLVE.01）。 */
        ARBITRATION_RELEASE,

        /** 裁决放款且净额为 0：待裁决 → 已完结（V6，零实付边界）。 */
        ARBITRATION_ZERO_COMPLETE,

        /** 裁决退款：待裁决 → 已退款（V6，FR-DISPUTE.RESOLVE.01）。 */
        ARBITRATION_REFUND
    }

    private SlotStateMachine() {
    }

    /**
     * 计算跃迁后的状态。
     *
     * @param from  当前状态
     * @param event 事件
     * @return 跃迁后的状态
     * @throws IllegalStateException 状态为空、事件为空或跃迁非法
     */
    public static SlotStatus transit(SlotStatus from, Event event) {
        if (from == null || event == null) {
            throw new IllegalStateException("名额状态跃迁的参数不能为空");
        }
        return switch (event) {
            case RELEASE -> {
                if (from != SlotStatus.SLOT_AVAILABLE) {
                    throw new IllegalStateException("名额 " + from + " 不能执行 RELEASE");
                }
                yield SlotStatus.SLOT_RELEASED;
            }
            case OCCUPY -> {
                if (from != SlotStatus.SLOT_AVAILABLE) {
                    throw new IllegalStateException("名额 " + from + " 不能执行 OCCUPY");
                }
                yield SlotStatus.SLOT_OCCUPIED;
            }
            case START -> {
                if (from != SlotStatus.SLOT_OCCUPIED) {
                    throw new IllegalStateException("名额 " + from + " 不能执行 START");
                }
                yield SlotStatus.SLOT_IN_PROGRESS;
            }
            case REOPEN -> {
                if (from != SlotStatus.SLOT_OCCUPIED) {
                    throw new IllegalStateException("名额 " + from + " 不能执行 REOPEN");
                }
                yield SlotStatus.SLOT_AVAILABLE;
            }
            case DELIVER -> {
                if (from != SlotStatus.SLOT_IN_PROGRESS) {
                    throw new IllegalStateException("名额 " + from + " 不能执行 DELIVER");
                }
                yield SlotStatus.SLOT_DELIVERED;
            }
            case ACCEPT -> {
                if (from != SlotStatus.SLOT_DELIVERED) {
                    throw new IllegalStateException("名额 " + from + " 不能执行 ACCEPT");
                }
                yield SlotStatus.SLOT_PENDING_SETTLEMENT;
            }
            case REJECT -> {
                if (from != SlotStatus.SLOT_DELIVERED) {
                    throw new IllegalStateException("名额 " + from + " 不能执行 REJECT");
                }
                yield SlotStatus.SLOT_PENDING_ARBITRATION;
            }
            case ZERO_PAY_COMPLETE -> {
                if (from != SlotStatus.SLOT_DELIVERED) {
                    throw new IllegalStateException("名额 " + from + " 不能执行 ZERO_PAY_COMPLETE");
                }
                yield SlotStatus.SLOT_COMPLETED;
            }
            case SETTLEMENT_SUCCEED -> {
                if (from != SlotStatus.SLOT_PENDING_SETTLEMENT) {
                    throw new IllegalStateException("名额 " + from + " 不能执行 SETTLEMENT_SUCCEED");
                }
                yield SlotStatus.SLOT_COMPLETED;
            }
            case SETTLEMENT_FAIL -> {
                if (from != SlotStatus.SLOT_PENDING_SETTLEMENT) {
                    throw new IllegalStateException("名额 " + from + " 不能执行 SETTLEMENT_FAIL");
                }
                yield SlotStatus.SLOT_PENDING_ARBITRATION;
            }
            case RECON_ROLLBACK -> {
                if (from != SlotStatus.SLOT_COMPLETED) {
                    throw new IllegalStateException("名额 " + from + " 不能执行 RECON_ROLLBACK");
                }
                yield SlotStatus.SLOT_PENDING_SETTLEMENT;
            }
            case ARBITRATION_WITHDRAW -> {
                if (from != SlotStatus.SLOT_PENDING_ARBITRATION) {
                    throw new IllegalStateException("名额 " + from + " 不能执行 ARBITRATION_WITHDRAW");
                }
                yield SlotStatus.SLOT_DELIVERED;
            }
            case ARBITRATION_RELEASE -> {
                if (from != SlotStatus.SLOT_PENDING_ARBITRATION) {
                    throw new IllegalStateException("名额 " + from + " 不能执行 ARBITRATION_RELEASE");
                }
                yield SlotStatus.SLOT_PENDING_SETTLEMENT;
            }
            case ARBITRATION_ZERO_COMPLETE -> {
                if (from != SlotStatus.SLOT_PENDING_ARBITRATION) {
                    throw new IllegalStateException(
                            "名额 " + from + " 不能执行 ARBITRATION_ZERO_COMPLETE");
                }
                yield SlotStatus.SLOT_COMPLETED;
            }
            case ARBITRATION_REFUND -> {
                if (from != SlotStatus.SLOT_PENDING_ARBITRATION) {
                    throw new IllegalStateException("名额 " + from + " 不能执行 ARBITRATION_REFUND");
                }
                yield SlotStatus.SLOT_REFUNDED;
            }
        };
    }
}
