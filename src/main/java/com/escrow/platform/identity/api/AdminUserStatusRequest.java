package com.escrow.platform.identity.api;

/**
 * 管理端禁用 / 启用账号的请求体（V7，FR-ADMIN.USER.01）。
 *
 * <p>{@code reason} 必填：账号状态变更必须能回答「为什么」，
 * 否则审计流水只有动作没有依据，等于没审计。</p>
 *
 * @param reason 变更原因，1~200 字
 * @since V7
 */
public record AdminUserStatusRequest(String reason) {
}
