package com.escrow.platform.fund.api;

/**
 * 模拟充值请求（FR-WALLET.RECHARGE.01，仅非生产环境开放）。
 *
 * @param amount    充值金额（分），必须 &gt; 0 且 ≤ 5,000,000
 * @param requestId 幂等键，1~32 字符；同一笔充值意图的重试必须复用同一个值。
 *                  不加业务前缀：唯一键是 {@code (biz_type, biz_no)} 复合的，
 *                  {@code biz_type} 已经区分业务，加前缀会超出 {@code biz_no} 的 32 字符列宽
 * @since V2
 */
public record RechargeRequest(Long amount, String requestId) {
}
