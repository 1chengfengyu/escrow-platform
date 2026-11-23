package com.escrow.platform.dispute.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 争议状态（SRS A.3.4，表 {@code dispute}）。
 *
 * <p>V4 只写 {@link #DISPUTE_PENDING}：雇主拒收即开争议；撤回与裁决归 V5。</p>
 *
 * @since V4
 */
public enum DisputeStatus {

    /** 待裁决：争议已发起，等待管理员处置。 */
    DISPUTE_PENDING,

    /** 已撤回：发起方在裁决前撤回。 */
    DISPUTE_WITHDRAWN,

    /** 已裁决：给出放款 / 退款结论。 */
    DISPUTE_RESOLVED;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
