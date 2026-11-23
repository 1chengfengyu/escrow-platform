package com.escrow.platform.settlement.domain;

/**
 * 模拟通道的七种注入模式（docs/03 V5「功能增量」第一条）。
 *
 * <p>模式只影响<b>模拟实现</b>的行为，不落库、不进协议；落库的仍是
 * {@link ChannelResult}（四种原始结果）与查证结论 {@link ChannelVerification}。
 * 分开的理由：把「注入手段」当成「业务状态」会把测试开关泄漏进数据模型。</p>
 *
 * <p>七种模式的语义：</p>
 * <ul>
 *   <li>{@code SUCCESS}：明确成功，金额一致；</li>
 *   <li>{@code FAIL}：明确失败；</li>
 *   <li>{@code TIMEOUT}：等到超时，结果未知；</li>
 *   <li>{@code LOST_RESPONSE}：没拿到应答，结果未知；</li>
 *   <li>{@code DUPLICATE_CALLBACK}：成功但回调投递两次（验证 T3 幂等）；</li>
 *   <li>{@code CHANNEL_MISSING}：本地已出站，通道侧查不到任何记录（查证返回 NOT_FOUND）；</li>
 *   <li>{@code AMOUNT_MISMATCH}：通道成功但实际出款与请求金额不一致。</li>
 * </ul>
 *
 * @since V5
 */
public enum ChannelMode {

    /** 明确成功。 */
    SUCCESS,

    /** 明确失败。 */
    FAIL,

    /** 超时。 */
    TIMEOUT,

    /** 应答丢失。 */
    LOST_RESPONSE,

    /** 成功但回调重复投递。 */
    DUPLICATE_CALLBACK,

    /** 通道侧无记录。 */
    CHANNEL_MISSING,

    /** 金额不一致。 */
    AMOUNT_MISMATCH;

    /**
     * 解析配置值，大小写不敏感。
     *
     * @param raw 配置原文
     * @return 模式
     * @throws IllegalArgumentException 非法取值
     */
    public static ChannelMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SUCCESS;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "escrow.payout.gateway-mode 只能是 SUCCESS / FAIL / TIMEOUT / LOST_RESPONSE / "
                            + "DUPLICATE_CALLBACK / CHANNEL_MISSING / AMOUNT_MISMATCH，实际=" + raw, ex);
        }
    }
}
