package com.escrow.platform.task.domain;

/**
 * 任务状态聚合的输入事实（SRS A.3.2：任务状态 = f(名额状态集合, 招募开关, 撤单标志)）。
 *
 * <p>纯值对象，只吃值只吐结果：九个名额状态计数由调用方从 {@code task_slot} 聚合得到，
 * {@code draft} / {@code cancelled} / {@code effectiveRecruitingClosed} 由调用方算好传入——
 * 聚合器不自己读时间，否则单测要 mock 时钟。</p>
 *
 * <p>{@code effectiveRecruitingClosed} = {@code cancelled || now >= endTime}
 * （SRS A.3.2 的独立属性定义）；{@code openDisputeCount} 为未裁决争议单数，
 * 草稿阶段与 v2-c2 内恒为 0，争议域接入后由调用方统计。</p>
 *
 * @param draft                      是否草稿（published_at IS NULL）
 * @param cancelled                  是否已撤单（cancelled_at IS NOT NULL）
 * @param effectiveRecruitingClosed  有效招募收口标志（撤单 OR 到期）
 * @param available                  待占用名额数
 * @param occupied                   已占用名额数
 * @param inProgress                 履约中名额数
 * @param delivered                  已交付名额数
 * @param pendingArbitration         待裁决名额数
 * @param pendingSettlement          待结算名额数
 * @param completed                  已完结名额数
 * @param refunded                   已退款名额数
 * @param released                   已释放名额数
 * @param openDisputeCount           未裁决争议单数
 * @since V2
 */
public record TaskStatusFacts(
        boolean draft,
        boolean cancelled,
        boolean effectiveRecruitingClosed,
        int available,
        int occupied,
        int inProgress,
        int delivered,
        int pendingArbitration,
        int pendingSettlement,
        int completed,
        int refunded,
        int released,
        int openDisputeCount) {

    /** 九个计数之和；草稿任务名额为零行，此处为 0。 */
    public int total() {
        return available + occupied + inProgress + delivered
                + pendingArbitration + pendingSettlement
                + completed + refunded + released;
    }

    /** 非终态名额数：待占用 + 已占用 + 履约中 + 已交付 + 待裁决 + 待结算。 */
    public int nonTerminal() {
        return available + occupied + inProgress + delivered
                + pendingArbitration + pendingSettlement;
    }

    /**
     * 是否全部名额终态。必须带 {@code total() > 0} 守卫：
     * <b>零名额任务不能被当成「全部终态」</b>，否则已发布但无名额的任务会落进
     * R4/R5/R6 显示「已完成/已取消」——这是聚合逻辑最容易写错的一处。
     */
    public boolean allTerminal() {
        return total() > 0 && nonTerminal() == 0;
    }
}
