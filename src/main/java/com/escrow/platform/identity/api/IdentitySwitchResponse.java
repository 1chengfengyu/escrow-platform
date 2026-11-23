package com.escrow.platform.identity.api;

/**
 * 切换身份响应（v1-c4 §2.4）。
 *
 * <p>只回新的当前身份：其余字段与 {@code /me} 完全重复，切换后前端需要时可再拉一次 {@code /me}。</p>
 *
 * @param currentIdentity 切换后本会话生效的身份
 * @since V1
 */
public record IdentitySwitchResponse(String currentIdentity) {
}
