package com.escrow.platform.identity.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 账号「注册时选择的默认身份」，对应 {@code platform_user.role}（docs/decisions.md #20）。
 *
 * <p>与 {@link IdentityType} 刻意分成两个枚举，不合并：</p>
 * <ul>
 *   <li>本枚举回答「这个账号注册时想以什么身份使用平台」，只决定登录后的默认视图；</li>
 *   <li>{@link IdentityType} 回答「这个账号实际开通了哪些身份」，业务授权一律以身份表为准
 *       （docs/decisions.md #19 文末「V1 修订」，SRS FR-ACCT.ROLE.01~ROLE.03）。</li>
 * </ul>
 *
 * <p>将来两者一定会分叉：例如 {@code ADMIN} 只能由后台开通，注册流程永远不会产生它。
 * 合并成一个枚举后，这种差异只能靠 if 判断弥补，是拆服务时的隐性耦合。</p>
 *
 * <p>落库值是枚举名（大写），由 MyBatis-Plus 的 {@link EnumValue} 字段写入；
 * domain 层除这一个 MP 注解外不依赖任何框架注解（docs/decisions.md #1 层间规则）。</p>
 *
 * @since V1
 */
public enum UserRole {

    /** 雇主：发布任务、托管资金。 */
    EMPLOYER,

    /** 劳动者：报名任务、交付履约。 */
    WORKER,

    /** 平台管理员：只由后台开通，注册流程不会产生该值。 */
    ADMIN;

    /**
     * 落库值。
     *
     * <p>取 {@code name()} 而不是另写一份字符串常量：否则枚举改名后库里还是旧值，
     * 这种漂移编译期发现不了，只会在查数据时暴露。</p>
     *
     * <p>不配全局 {@code default-enum-type-handler}（docs/decisions.md #20）：全局 handler 会把
     * 没有 {@link EnumValue} 的枚举也接管，行为不可预测。</p>
     */
    @EnumValue
    private final String code = name();

    /**
     * 手写 getter，不引 Lombok：domain 层保持零框架依赖，且枚举只有四个文件，
     * 手写成本远低于「有人误加 @Data」的风险（docs/decisions.md #39）。
     */
    public String getCode() {
        return code;
    }
}
