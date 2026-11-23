package com.escrow.platform.dispute.api;

import jakarta.validation.constraints.Size;

/**
 * 劳动者申诉开争议的请求（V6，FR-DISPUTE.OPEN.01）。
 *
 * @param reason 申诉理由，最长 500 字；为空时使用默认理由
 * @since V6
 */
public record DisputeAppealRequest(@Size(max = 500, message = "reason 最长 500 字") String reason) {
}
