package com.escrow.platform.fund.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 分录方向（SRS 3.4.1「借 = 流出方 / 贷 = 流入方」）。
 *
 * <p>金额恒为正，方向只由本列表达；禁止用负数金额表示反向。</p>
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】）。</p>
 *
 * @since V2
 */
public enum JournalDirection {

    /** 借：资金流出方。 */
    DEBIT,

    /** 贷：资金流入方。 */
    CREDIT;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
