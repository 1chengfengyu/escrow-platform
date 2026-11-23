package com.escrow.platform.settlement.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 通道原始结果（payout_order.channel_result）。
 *
 * <p>TIMEOUT 与 LOST_RESPONSE 不是同义词：前者是等到超时，后者是没拿到应答。二者都驱动打款单进入
 * {@code PAYOUT_PENDING_VERIFICATION}（未知待查证）。查证后的 NOT_FOUND / AMOUNT_MISMATCH 属判定结论，
 * 不落这一列。</p>
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】）。</p>
 *
 * @since V2
 */
public enum ChannelResult {

    /** 通道明确成功。 */
    SUCCESS,

    /** 通道明确失败。 */
    FAIL,

    /** 请求超时，结果未知。 */
    TIMEOUT,

    /** 应答丢失，结果未知。 */
    LOST_RESPONSE;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
