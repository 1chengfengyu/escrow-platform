package com.escrow.platform.identity.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 雇主资质的主体类型（FR-ACCT.QUAL.01，SRS 4.2.2「雇主资质」行）。
 *
 * <p>只有两个取值：个人与个体工商户。企业主体不在本期 SRS 范围内，
 * 不提前加常量——多一个没人调用的常量，就是多一处将来要维护的分支。</p>
 *
 * <p>落库值是枚举名（大写），由 {@link EnumValue} 字段写入（docs/decisions.md #20）。</p>
 *
 * @since V1
 */
public enum SubjectType {

    /** 个人。 */
    INDIVIDUAL("个人"),

    /** 个体工商户。 */
    SOLE_PROPRIETOR("个体工商户");

    /** 落库值，取 {@code name()}，理由与 {@link IdentityType} 一致。 */
    @EnumValue
    private final String code = name();

    /** 中文展示名，只用于文案，不落库。 */
    private final String label;

    SubjectType(String label) {
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }
}
