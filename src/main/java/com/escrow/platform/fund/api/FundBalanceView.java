package com.escrow.platform.fund.api;

/**
 * 本人资金账户余额视图（FR-WALLET.QUERY.01）。
 *
 * <p>金额单位分，序列化为字符串（全局 Long → String，避免 JS 精度丢失）。</p>
 *
 * @param available 可用余额（分）
 * @param frozen    冻结余额（分）
 * @since V2
 */
public record FundBalanceView(long available, long frozen) {
}
