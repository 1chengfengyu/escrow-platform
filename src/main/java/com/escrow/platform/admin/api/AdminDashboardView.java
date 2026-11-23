package com.escrow.platform.admin.api;

/**
 * 治理看板聚合视图（V7，SRS 未单列，见 docs/decisions.md #51）。
 *
 * <p>只做「各域已有计数的汇总」，不在这里定义新口径：
 * 待人工打款来自结算域，待裁决争议来自争议域，待处理差异来自对账处置服务。</p>
 *
 * @param userCount              账号总数
 * @param taskCount              任务总数（含草稿与已撤单）
 * @param pendingPayoutCount     待人工处理打款单数
 * @param pendingDisputeCount    待裁决争议数
 * @param pendingDifferenceCount 待处理对账差异数
 * @since V7
 */
public record AdminDashboardView(
        long userCount,
        long taskCount,
        long pendingPayoutCount,
        long pendingDisputeCount,
        long pendingDifferenceCount) {
}
