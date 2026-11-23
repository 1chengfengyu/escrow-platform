package com.escrow.platform.settlement.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 查证结论（SRS FR-PAY.UNKNOWN.01~.03 的五种返回）。
 *
 * <p>与 {@link ChannelResult} 的区别：后者是「第一次出站时通道说了什么」，
 * 本枚举是「事后向通道查证时查到了什么」。{@code NOT_FOUND} 表示通道侧确实没有这笔，
 * 才可以判失败并重试；{@code ERROR} 表示查证本身失败，必须原状态等待下次，
 * 绝不能当成 {@code NOT_FOUND}。</p>
 *
 * <p>落库值取 {@code name()}（docs/decisions.md #20）。</p>
 *
 * @since V5
 */
public enum ChannelVerification {

    /** 查到且金额一致：可按成功落账。 */
    SUCCESS,

    /** 查到且通道明确失败。 */
    FAIL,

    /** 通道侧无此请求号：可判失败，允许重试。 */
    NOT_FOUND,

    /** 查到但金额与请求不一致：只能人工。 */
    AMOUNT_MISMATCH,

    /** 查证调用本身失败（网络 / 通道不可用）：保持未知，下次再查。 */
    ERROR;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }

    /** 是否允许据此判失败并进入重试。 */
    public boolean allowsRetry() {
        return this == NOT_FOUND || this == FAIL;
    }
}
