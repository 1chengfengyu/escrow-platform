package com.escrow.platform.settlement.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 人工处置动作（FR-PAY.MANUAL.01~.03 的三选一）。
 *
 * <p>{@code TO_DIFFERENCE} 在 V5 只落一条处置记录并保持待人工，差异单由 V6 对账生成；
 * 这是刻意的分期，避免在 V5 提前写一个没有对账日、没有通道记录的差异单。</p>
 *
 * @since V5
 */
public enum PayoutManualDecisionType {

    /** 以通道实际金额为准完成结算（余额差额退回雇主）。 */
    CONFIRM_ACTUAL,

    /** 零扣款：劳动者实付 0，服务费照收，其余退回雇主。 */
    ZERO_PAYMENT,

    /** 转差异：本版只记录，等 V6 对账处置。 */
    TO_DIFFERENCE;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
