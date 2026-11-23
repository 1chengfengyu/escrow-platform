package com.escrow.platform.identity.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 已开通的身份类型，对应 {@code platform_user_identity.identity_type}
 * （docs/decisions.md #18 表名预览、SRS 4.2.2「用户身份」行）。
 *
 * <p>SRS FR-ACCT.ROLE.01 要求「同一账号可同时具备雇主与劳动者身份」，
 * 因此身份不是账号上的一个字段，而是本表里的多行；唯一键
 * {@code uk_user_identity (user_id, identity_type)} 保证同一身份不会重复开通。</p>
 *
 * <p>不并入 {@link UserRole}：本枚举是「已经开通的身份」，{@link UserRole} 是
 * 「注册时选的默认视图」，未来会分叉（ADMIN 只能后台开通）。</p>
 *
 * <p>落库值是枚举名（大写），由 MyBatis-Plus 的 {@link EnumValue} 字段写入。</p>
 *
 * @since V1
 */
public enum IdentityType {

    /** 雇主身份。 */
    EMPLOYER,

    /** 劳动者身份。 */
    WORKER,

    /** 平台管理员身份，只由后台开通。 */
    ADMIN;

    /** 落库值，取 {@code name()}，理由见 {@link UserRole#getCode()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
