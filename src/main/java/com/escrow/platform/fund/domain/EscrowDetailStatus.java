package com.escrow.platform.fund.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 托管明细状态（SRS A.3.3「托管明细状态机」）。
 *
 * <p>释放 / 退款的最小单位；已释放 / 已退款是终态，唯一出边是差异①回退重试。</p>
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】）。</p>
 *
 * @since V2
 */
public enum EscrowDetailStatus {

    /** 待托管：名额报名完成但保证金尚未冻结。 */
    ESCROW_DETAIL_PENDING_HOLD,

    /** 已托管：保证金已冻结。 */
    ESCROW_DETAIL_HELD,

    /** 释放中：任务验收合格，保证金正在解冻。 */
    ESCROW_DETAIL_RELEASING,

    /** 已释放：保证金已解冻并进入结算。 */
    ESCROW_DETAIL_RELEASED,

    /** 已退款：任务取消或验收失败，保证金已退回雇主。 */
    ESCROW_DETAIL_REFUNDED,

    /** 冻结：争议期间保证金继续冻结。 */
    ESCROW_DETAIL_FROZEN;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
