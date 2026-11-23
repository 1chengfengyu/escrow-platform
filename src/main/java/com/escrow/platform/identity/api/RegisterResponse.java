package com.escrow.platform.identity.api;

/**
 * 注册响应：只回 {@code userId}，不回口令哈希、邮箱与手机号（NFR-SEC.04）。
 *
 * <p>不手写 {@code @JsonSerialize}：{@code Long} 的字符串化是全局契约，
 * 由 ObjectMapper 统一处理，单点配置优于逐字段注解。</p>
 *
 * @param userId 新账号的雪花 ID
 * @since V1
 */
public record RegisterResponse(Long userId) {
}
