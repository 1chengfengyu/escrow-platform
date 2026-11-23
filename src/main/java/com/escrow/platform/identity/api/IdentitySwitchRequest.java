package com.escrow.platform.identity.api;

/**
 * 切换身份请求（FR-ACCT.ROLE.03）。
 *
 * <p>只接受 {@code EMPLOYER} / {@code WORKER}：管理员身份只能由后台开通，
 * 不允许自助切换（docs/decisions.md #20「注册流程永远不产生 ADMIN」）。</p>
 *
 * @param identityType 目标身份，取值 {@code EMPLOYER} 或 {@code WORKER}
 * @since V1
 */
public record IdentitySwitchRequest(String identityType) {
}
