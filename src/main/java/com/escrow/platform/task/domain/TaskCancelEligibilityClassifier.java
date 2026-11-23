package com.escrow.platform.task.domain;

/**
 * 撤单资格分类器（卡片 §1.3 第 7 条的裁决），纯静态函数、无状态。
 *
 * <p>顺序固定，命中即返回：</p>
 * <ol>
 *   <li>草稿或零名额 → {@link TaskCancelEligibility#NOT_CANCELABLE}</li>
 *   <li>全部名额 {@link SlotStatus#SLOT_AVAILABLE} → {@link TaskCancelEligibility#CANCELABLE}</li>
 *   <li>存在非终态且非待占用的名额（已占用 / 履约中 / 已交付 / 待裁决 / 待结算）
 *       → {@link TaskCancelEligibility#HAS_ACTIVE_SLOT}</li>
 *   <li>其余（如「部分待占用 + 部分终态」的混合情形）→ {@link TaskCancelEligibility#NOT_CANCELABLE}</li>
 * </ol>
 *
 * <p>混合情形归 {@code NOT_CANCELABLE} 的理由（卡片 §1.3 第 7 条）：SRS FR-TASK.CANCEL.01
 * 要求「全部名额均为待占用」，混合情形不满足；FR-TASK.CANCEL.02 只覆盖
 * 「有活跃名额」与「全部终态」两种拒绝码，混合情形归 {@code TASK_NOT_CANCELABLE}
 * 更接近语义（不能撤），且不会让已完结名额被误退款。</p>
 *
 * @since V2
 */
public final class TaskCancelEligibilityClassifier {

    private TaskCancelEligibilityClassifier() {
    }

    /** 按固定顺序分类撤单资格。 */
    public static TaskCancelEligibility classify(TaskStatusFacts f) {
        // ① 草稿 / 零名额：撤单只对已发布且有名额的任务有意义
        if (f.draft() || f.total() == 0) {
            return TaskCancelEligibility.NOT_CANCELABLE;
        }
        // ② 全部名额待占用：FR-TASK.CANCEL.01 的唯一可撤前提
        if (f.available() == f.total()) {
            return TaskCancelEligibility.CANCELABLE;
        }
        // ③ 存在活跃名额（非终态且非待占用）：FR-TASK.CANCEL.02 的 TASK_HAS_ACTIVE_SLOT
        if (f.occupied() + f.inProgress() + f.delivered()
                + f.pendingArbitration() + f.pendingSettlement() > 0) {
            return TaskCancelEligibility.HAS_ACTIVE_SLOT;
        }
        // ④ 其余（如「部分待占用 + 部分终态」混合）：TASK_NOT_CANCELABLE
        return TaskCancelEligibility.NOT_CANCELABLE;
    }
}
