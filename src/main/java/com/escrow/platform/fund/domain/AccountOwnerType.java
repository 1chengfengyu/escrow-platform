package com.escrow.platform.fund.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 账户归属类型（SRS 3.4.1 / 4.2.2「资金账户」）。
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】）。</p>
 *
 * @since V2
 */
public enum AccountOwnerType {

    /** 个人用户账户：必须绑定 user_id。 */
    USER,

    /** 平台系统账户：全局唯一，user_id 为空。 */
    SYSTEM;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
