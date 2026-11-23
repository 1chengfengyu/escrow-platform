package com.escrow.platform.fund.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 托管单状态（SRS 3.4.2「托管」）。
 *
 * <p>SRS 只给托管单一个「状态」字段，没有给状态机。V2 首次落地取最小二态：
 * HELD（已托管）/ CLOSED（已关闭）。任务进度由托管明细派生，不在这里堆状态。</p>
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】）。</p>
 *
 * @since V2
 */
public enum EscrowOrderStatus {

    /** 已托管：保证金被冻结，托管单处于有效状态。 */
    ESCROW_ORDER_HELD,

    /** 已关闭：所有明细均进入终态（已释放 / 已退款），托管单结算完毕。 */
    ESCROW_ORDER_CLOSED;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
