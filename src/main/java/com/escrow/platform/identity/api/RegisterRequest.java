package com.escrow.platform.identity.api;

/**
 * 注册请求（FR-ACCT.REG.01），字段固定 5 个，不再扩张。
 *
 * <p>刻意不加 {@code @Valid}：注解校验失败会抛 {@code BindException}，被全局处理器
 * 兜成 {@code VALIDATION_FAILED}；SRS 要求注册字段错误返回 {@code INVALID_PARAMETER}
 * 并在 {@code data.fields} 指明出错字段，因此校验由
 * {@link com.escrow.platform.identity.application.RegisterValidator} 手工完成。</p>
 *
 * @param phone    手机号，可空；与 {@code email} 至少填一个
 * @param email    邮箱，可空；与 {@code phone} 至少填一个
 * @param password 明文口令，仅在本层与哈希调用之间停留，禁止落日志
 * @param nickname 昵称
 * @param role     注册时选择的默认身份，只允许 {@code EMPLOYER} / {@code WORKER}
 * @since V1
 */
public record RegisterRequest(
        String phone,
        String email,
        String password,
        String nickname,
        String role) {
}
