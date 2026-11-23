package com.escrow.platform.identity.api;

/**
 * 登录请求（FR-ACCT.LOGIN.01~LOGIN.03）。
 *
 * <p>{@code account} 是手机号或邮箱：含 {@code @} 视为邮箱（trim 后判断），否则按手机号查。
 * 不做格式预校验——格式不合法的账号查不到人，同样落到 401，
 * 顺带满足 LOGIN.03「不泄露账号是否存在」。</p>
 *
 * @param account  手机号或邮箱
 * @param password 明文口令，只在本层与 {@code matches} 调用之间停留，禁止落日志
 * @since V1
 */
public record LoginRequest(String account, String password) {
}
