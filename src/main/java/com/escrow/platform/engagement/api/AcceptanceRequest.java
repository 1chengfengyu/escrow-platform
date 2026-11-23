package com.escrow.platform.engagement.api;

/**
 * 验收请求体（FR-ACCEPT.MANUAL.01）。
 *
 * @param decision 结论：{@code PASS} / {@code REJECT}
 * @param reason   拒收理由，通过时忽略
 * @since V4
 */
public record AcceptanceRequest(String decision, String reason) {
}
