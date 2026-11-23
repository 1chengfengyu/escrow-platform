package com.escrow.platform.task.domain;

/**
 * 任务展示状态（SRS A.3.2「任务状态派生」R0~R12），<b>只用于展示，绝不落库</b>
 * ——任务状态是名额集合、招募开关与撤单标志的派生量，不存列（docs/decisions.md #47）。
 *
 * <p>聚合入口是 {@link TaskStatusAggregator#aggregate}，严格按 R0~R12 顺序命中。</p>
 *
 * @since V2
 */
public enum TaskDisplayStatus {

    /** 草稿（R0）：尚未发布，其他用户不可见（FR-TASK.CREATE.03）。 */
    TASK_DRAFT,

    /** 招募中（R1）：存在待占用名额且招募未收口。 */
    TASK_RECRUITING,

    /** 争议中（R2）：存在待裁决名额且有未裁决争议单。 */
    TASK_IN_DISPUTE,

    /** 结算异常（R3）：存在待裁决名额但无未裁决争议单（打款人工失败产生）。 */
    TASK_SETTLEMENT_ABNORMAL,

    /** 已取消（R4）：全部名额终态且撤单标志为真。 */
    TASK_CANCELLED,

    /** 已完成（R5）：全部名额终态且至少一个已完结。 */
    TASK_COMPLETED,

    /** 已关闭（R6）：全部名额终态且无已完结；R12 兜底复用本值（SRS 的 R6 与 R12 都映射「已关闭」）。 */
    TASK_CLOSED,

    /** 结算中（R7）：存在待结算名额。 */
    TASK_SETTLING,

    /** 待验收（R8）：存在已交付名额。 */
    TASK_PENDING_ACCEPTANCE,

    /** 履约中（R9）：存在履约中名额。 */
    TASK_IN_PROGRESS,

    /** 已接单（R10）：存在已占用、无待占用且招募未收口。 */
    TASK_TAKEN,

    /** 已关闭招募（R11）：招募已收口但仍有非终态名额在途，不是终态。 */
    TASK_RECRUITING_CLOSED
}
