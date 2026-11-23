package com.escrow.platform.identity.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 账号状态，对应 {@code platform_user.status}（docs/decisions.md #20 的取值表、#15）。
 *
 * <p>注销不是删除：库里没有 {@code deleted} 字段（docs/decisions.md #15），
 * 「用户主动注销」用 {@link #CLOSED} 表达，历史资金流水与对账记录仍然可查。</p>
 *
 * <p>落库值是枚举名（大写），由 MyBatis-Plus 的 {@link EnumValue} 字段写入。</p>
 *
 * @since V1
 */
public enum UserStatus {

    /** 正常，可以登录。 */
    ACTIVE,

    /** 被平台封禁，不能登录，但数据保留。 */
    DISABLED,

    /** 用户主动注销，不能登录；不是删除，历史数据必须仍可查。 */
    CLOSED;

    /** 落库值，取 {@code name()}，理由见 {@link UserRole#getCode()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
