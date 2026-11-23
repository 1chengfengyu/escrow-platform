package com.escrow.platform.settlement.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 对账差异类型（settlement_difference.diff_type，SRS 3.4.6）。
 *
 * <p>四类不得合并（SRS P1-06：不许一律写 AMOUNT_MISMATCH）。
 * AMOUNT_MISMATCH 必须同时有本地金额与通道金额，由 {@code ck_settlement_difference_amount_mismatch} 兜住。</p>
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】）。</p>
 *
 * @since V2
 */
public enum DifferenceType {

    /** 通道缺失：本地有记录但通道查不到。 */
    CHANNEL_MISSING,

    /** 应答丢失：请求超时或应答乱序。 */
    LOST_RESPONSE,

    /** 金额不一致：本地请求额与通道实际出款额不符。 */
    AMOUNT_MISMATCH,

    /** 结果未定：通道返回未知态，需要查证后再分类。 */
    RESULT_UNDETERMINED;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
