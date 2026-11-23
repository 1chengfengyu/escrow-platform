package com.escrow.platform.engagement.domain;

/**
 * 签约状态机（SRS A.3.1「名额状态机」的签约侧，FR-SIGN.START.01 / FR-SIGN.CANCEL.01）。
 *
 * <p>已落地四条跃迁：{@code CONTRACT_ACTIVE -> CONTRACT_STARTED}（开始履约）、
 * {@code CONTRACT_ACTIVE -> CONTRACT_CANCELLED}（劳动者取消 / 未开始自动取消）、
 * {@code CONTRACT_STARTED -> CONTRACT_DELIVERED}（提交交付物，v3-c4）、
 * {@code CONTRACT_DELIVERED -> CONTRACT_COMPLETED}（验收通过，v4-c1）。
 * 拒收不改签约状态：合同仍停在「已交付」，由争议裁决决定后续走向。</p>
 *
 * <p><b>取消只允许从「生效中」出发</b>：{@code CONTRACT_STARTED} / {@code CONTRACT_DELIVERED}
 * 已进入履约，劳动者不得单方取消（FR-SIGN.CANCEL.03），只能走申诉。</p>
 *
 * <p>非法跃迁抛 {@link IllegalStateException}：这是服务层守卫写漏，不是用户可纠正的输入。</p>
 *
 * @since V3
 */
public final class ContractStateMachine {

    /** 签约状态跃迁事件。 */
    public enum Event {

        /** 劳动者点击「开始履约」（FR-SIGN.START.01）。 */
        START,

        /** 劳动者取消签约，或签约生效超时未开始被系统自动取消（FR-SIGN.CANCEL.01 / START.02）。 */
        CANCEL,

        /** 劳动者提交交付物（FR-DELIVER.SUBMIT.02）。 */
        DELIVER,

        /** 雇主（或 48h 自动验收）通过验收（FR-ACCEPT.RESULT.01）。 */
        ACCEPT,

        /** 争议裁决退款：已交付 → 已取消（V6，FR-DISPUTE.RESOLVE.01）。 */
        ARBITRATION_CANCEL
    }

    private ContractStateMachine() {
    }

    /**
     * 计算跃迁后的状态。
     *
     * @param from  当前状态
     * @param event 事件
     * @return 跃迁后的状态
     * @throws IllegalStateException 参数为空、签约已进入履约、或已是终态
     */
    public static ContractStatus transit(ContractStatus from, Event event) {
        if (from == null || event == null) {
            throw new IllegalStateException("签约状态跃迁的参数不能为空");
        }
        return switch (event) {
            case START -> {
                requireActive(from, event);
                yield ContractStatus.CONTRACT_STARTED;
            }
            case CANCEL -> {
                requireActive(from, event);
                yield ContractStatus.CONTRACT_CANCELLED;
            }
            // 交付只允许从「履约中」出发：未开始就交付没有履约过程，必须先 START
            case DELIVER -> {
                if (from != ContractStatus.CONTRACT_STARTED) {
                    throw new IllegalStateException("签约 " + from + " 不能执行 " + event);
                }
                yield ContractStatus.CONTRACT_DELIVERED;
            }
            case ACCEPT -> {
                if (from != ContractStatus.CONTRACT_DELIVERED) {
                    throw new IllegalStateException("签约 " + from + " 不能执行 " + event);
                }
                yield ContractStatus.CONTRACT_COMPLETED;
            }
            case ARBITRATION_CANCEL -> {
                if (from != ContractStatus.CONTRACT_DELIVERED) {
                    throw new IllegalStateException("签约 " + from + " 不能执行 " + event);
                }
                yield ContractStatus.CONTRACT_CANCELLED;
            }
        };
    }

    private static void requireActive(ContractStatus from, Event event) {
        if (from != ContractStatus.CONTRACT_ACTIVE) {
            throw new IllegalStateException("签约 " + from + " 不能执行 " + event);
        }
    }
}
