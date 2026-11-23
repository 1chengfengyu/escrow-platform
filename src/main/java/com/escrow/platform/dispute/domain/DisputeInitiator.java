package com.escrow.platform.dispute.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 争议发起方（dispute.initiator_type）。
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】）。</p>
 *
 * @since V2
 */
public enum DisputeInitiator {

    /** 雇主发起争议。 */
    EMPLOYER,

    /** 劳动者发起争议。 */
    WORKER;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
