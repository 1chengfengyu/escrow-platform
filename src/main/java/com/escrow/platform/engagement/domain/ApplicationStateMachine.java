package com.escrow.platform.engagement.domain;

/**
 * 报名单状态机（SRS A.3.7「报名单」）。
 *
 * <p>已落地三条跃迁：{@code APPLY_PENDING -> APPLY_HIRED}（录用，FR-SIGN.HIRE.01）、
 * {@code APPLY_PENDING -> APPLY_REJECTED}（满员 / 撤单 / 到期，FR-SIGN.APPLY.08）、
 * {@code APPLY_HIRED -> APPLY_WITHDRAWN}（取消签约 / 未开始自动取消，FR-SIGN.CANCEL.01 / START.02）。
 * 待处理状态的「主动撤回」接口归后续卡，不提前写空壳。</p>
 *
 * <p>非法跃迁抛 {@link IllegalStateException}：这是服务层守卫写漏，不是用户可纠正的输入，
 * 不能转成 4xx 业务码。</p>
 *
 * @since V3
 */
public final class ApplicationStateMachine {

    /** 报名单状态跃迁事件。 */
    public enum Event {

        /** 雇主录用。 */
        HIRE,

        /** 满员 / 任务撤单 / 已过结束时间导致的自动拒绝。 */
        REJECT,

        /** 已录用后签约被取消（劳动者主动 / 未开始超时），报名随之撤回。 */
        WITHDRAW
    }

    private ApplicationStateMachine() {
    }

    /**
     * 计算跃迁后的状态。
     *
     * @param from  当前状态
     * @param event 事件
     * @return 跃迁后的状态
     * @throws IllegalStateException 状态为空、事件为空或跃迁非法
     */
    public static ApplicationStatus transit(ApplicationStatus from, Event event) {
        if (from == null || event == null) {
            throw new IllegalStateException("报名状态跃迁的参数不能为空");
        }
        return switch (event) {
            case HIRE -> {
                if (from != ApplicationStatus.APPLY_PENDING) {
                    throw new IllegalStateException("报名 " + from + " 不能执行 HIRE");
                }
                yield ApplicationStatus.APPLY_HIRED;
            }
            case REJECT -> {
                if (from != ApplicationStatus.APPLY_PENDING) {
                    throw new IllegalStateException("报名 " + from + " 不能执行 REJECT");
                }
                yield ApplicationStatus.APPLY_REJECTED;
            }
            case WITHDRAW -> {
                if (from != ApplicationStatus.APPLY_HIRED) {
                    throw new IllegalStateException("报名 " + from + " 不能执行 WITHDRAW");
                }
                yield ApplicationStatus.APPLY_WITHDRAWN;
            }
        };
    }
}
