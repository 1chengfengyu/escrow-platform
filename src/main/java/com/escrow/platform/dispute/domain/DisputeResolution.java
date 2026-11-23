package com.escrow.platform.dispute.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 争议处置结论（dispute.resolution）。
 *
 * <p>与 {@code ck_dispute_resolved} 配合：DISPUTE_RESOLVED 必须有值，其余状态必须为 NULL。</p>
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】）。</p>
 *
 * @since V2
 */
public enum DisputeResolution {

    /** 判定放款：保证金释放给劳动者。 */
    RELEASE,

    /** 判定退款：保证金退回雇主。 */
    REFUND;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
