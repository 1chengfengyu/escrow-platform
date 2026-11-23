package com.escrow.platform.identity.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 单个身份的状态，对应 {@code platform_user_identity.status}
 * （SRS FR-ACCT.ROLE.02：发起需身份上下文的操作时，校验当前身份是否「已开通且未被禁用」）。
 *
 * <p>与 {@link UserStatus} 的区别：账号被封禁（{@code UserStatus.DISABLED}）影响所有身份；
 * 这里禁用的是某一个身份（例如劳动者的报名权限被单独停用），账号本身仍可登录。</p>
 *
 * <p>落库值是枚举名（大写），由 MyBatis-Plus 的 {@link EnumValue} 字段写入。</p>
 *
 * @since V1
 */
public enum IdentityStatus {

    /** 身份已开通且可用。 */
    ACTIVE,

    /** 身份被禁用：对应操作返回 {@code IDENTITY_NOT_AVAILABLE}（FR-ACCT.ROLE.02）。 */
    DISABLED;

    /** 落库值，取 {@code name()}，理由见 {@link UserRole#getCode()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
