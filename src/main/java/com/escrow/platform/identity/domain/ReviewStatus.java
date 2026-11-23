package com.escrow.platform.identity.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 雇主资质的四态（FR-ACCT.QUAL.02：未提交 / 待审核 / 已通过 / 已驳回）。
 *
 * <p>{@link #UNSUBMITTED} 是<b>业务态而不是存储态</b>：没提交时库里没有这一行，
 * 对外查询把「无记录」翻译成 {@code UNSUBMITTED}。这样「从未提交」只有一个真相源，
 * 查询不需要对空行做 {@code COALESCE}，DDL 的 CHECK 也能把非法值挡在库外。</p>
 *
 * <p>落库值是枚举名（大写），由 {@link EnumValue} 字段写入（docs/decisions.md #20）。</p>
 *
 * @since V1
 */
public enum ReviewStatus {

    /** 未提交：仅作为接口返回值，永不落库。 */
    UNSUBMITTED,

    /** 待审核。 */
    PENDING,

    /** 已通过。 */
    APPROVED,

    /** 已驳回。 */
    REJECTED;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
