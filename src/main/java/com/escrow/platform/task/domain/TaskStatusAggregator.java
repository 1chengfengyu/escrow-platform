package com.escrow.platform.task.domain;

/**
 * 任务展示状态聚合器（SRS A.3.2 R0~R12），纯静态函数、无状态、无 Spring 依赖。
 *
 * <p>顺序即语义，命中即返回，<b>不许调换顺序</b>：</p>
 * <ul>
 *   <li>R0 必须最先：草稿没有名额，排后面会被 R12 兜成「已关闭」（FR-TASK.CREATE.03）；</li>
 *   <li>R1 必须在 R2/R3、R7~R10 之前（SRS v1.2 P1-01 与 v1.1 P1-01）：
 *       多名额任务只要还剩待占用且未收口，就必须显示「招募中」，
 *       否则任务从招募列表消失却报得上名，剩余名额无人补；</li>
 *   <li>R4 的 {@code cancelled} 判定在 R5/R6 之前：全部终态时先看撤单标志。</li>
 * </ul>
 *
 * <p>任务状态只用于展示，不作为业务守卫（SRS A.3.2）；报名 / 录用 / 撤单的守卫
 * 一律直接判定名额集合与时间。</p>
 *
 * @since V2
 */
public final class TaskStatusAggregator {

    private TaskStatusAggregator() {
    }

    /** 按 R0~R12 优先级取第一个命中的规则。 */
    public static TaskDisplayStatus aggregate(TaskStatusFacts f) {
        // R0 草稿（草稿没有名额，不能走 R12 变「已关闭」）
        if (f.draft()) {
            return TaskDisplayStatus.TASK_DRAFT;
        }
        // R1 招募中（必须压过 R2/R3 与 R7~R10）
        if (f.available() > 0 && !f.effectiveRecruitingClosed()) {
            return TaskDisplayStatus.TASK_RECRUITING;
        }
        // R2 争议中
        if (f.pendingArbitration() > 0 && f.openDisputeCount() > 0) {
            return TaskDisplayStatus.TASK_IN_DISPUTE;
        }
        // R3 结算异常（待裁决但无未裁决争议单）
        if (f.pendingArbitration() > 0 && f.openDisputeCount() == 0) {
            return TaskDisplayStatus.TASK_SETTLEMENT_ABNORMAL;
        }
        // R4 已取消（全部终态时先看撤单标志）
        if (f.allTerminal() && f.cancelled()) {
            return TaskDisplayStatus.TASK_CANCELLED;
        }
        // R5 已完成
        if (f.allTerminal() && f.completed() > 0) {
            return TaskDisplayStatus.TASK_COMPLETED;
        }
        // R6 已关闭
        if (f.allTerminal()) {
            return TaskDisplayStatus.TASK_CLOSED;
        }
        // R7 结算中
        if (f.pendingSettlement() > 0) {
            return TaskDisplayStatus.TASK_SETTLING;
        }
        // R8 待验收
        if (f.delivered() > 0) {
            return TaskDisplayStatus.TASK_PENDING_ACCEPTANCE;
        }
        // R9 履约中
        if (f.inProgress() > 0) {
            return TaskDisplayStatus.TASK_IN_PROGRESS;
        }
        // R10 已接单
        if (f.occupied() > 0 && f.available() == 0 && !f.effectiveRecruitingClosed()) {
            return TaskDisplayStatus.TASK_TAKEN;
        }
        // R11 已关闭招募（非终态，在途名额继续走完）
        if (f.effectiveRecruitingClosed() && f.nonTerminal() > 0) {
            return TaskDisplayStatus.TASK_RECRUITING_CLOSED;
        }
        // R12 兜底（复用「已关闭」，SRS 的 R6 与 R12 都映射已关闭）
        return TaskDisplayStatus.TASK_CLOSED;
    }

    /** 列表角标：存在未裁决争议单（SRS A.3.2 异常标记 {@code has_open_dispute}）。 */
    public static boolean hasOpenDispute(TaskStatusFacts f) {
        return f.openDisputeCount() > 0;
    }

    /** 列表角标：存在待裁决名额但无未裁决争议单（SRS A.3.2 异常标记 {@code has_settlement_abnormal}）。 */
    public static boolean hasSettlementAbnormal(TaskStatusFacts f) {
        return f.pendingArbitration() > 0 && f.openDisputeCount() == 0;
    }
}
