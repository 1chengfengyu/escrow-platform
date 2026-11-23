package com.escrow.platform.settlement.domain;

/**
 * 通道流水的一条记录（V6，FR-RECON.DIFF.01）。
 *
 * <p>只保留对账所需的最小字段：请求号用于与本地打款单对齐，
 * 是否成功与金额用于分类差异；通道流水号在模拟实现里等于请求号。</p>
 *
 * @param requestNo 通道侧请求号（与本地 {@code payout_request_no} 对齐）
 * @param amount    通道记账金额（分）
 * @param success   通道是否视为成功
 * @since V6
 */
public record ChannelTxn(String requestNo, long amount, boolean success) {
}
