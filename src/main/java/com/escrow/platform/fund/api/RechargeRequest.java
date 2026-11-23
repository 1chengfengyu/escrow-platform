package com.escrow.platform.fund.api;

/**
 * 模拟充值请求（FR-WALLET.RECHARGE.01，仅非生产环境开放）。
 *
 * @param amount 充值金额（分），必须 &gt; 0 且 ≤ 5,000,000
 * @since V2
 */
public record RechargeRequest(Long amount) {
}
