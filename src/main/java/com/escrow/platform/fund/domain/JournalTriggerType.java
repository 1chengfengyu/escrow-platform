package com.escrow.platform.fund.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 凭证批次触发方（fund_journal_batch.trigger_type）。
 *
 * <p>与 {@code ck_fund_journal_batch_trigger} 的三个值一一对应。</p>
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】）。</p>
 *
 * @since V2
 */
public enum JournalTriggerType {

    /** 用户行为触发（如雇主付款、雇主发起退款）。 */
    USER,

    /** 系统定时 / 自动触发（如对账、定时结算）。 */
    SYSTEM,

    /** 运营人工触发（如人工补记、人工处置差异）。 */
    OPERATOR;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
