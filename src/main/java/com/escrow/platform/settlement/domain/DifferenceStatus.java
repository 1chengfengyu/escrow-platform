package com.escrow.platform.settlement.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 差异单状态（settlement_difference.status）。
 *
 * <p>与处置结论的联动由 {@code ck_settlement_difference_resolved} 钉死：
 * 待处理必须无处置人 / 时间；已处理必须带处置人 / 时间且处置结果为补记 / 冲正 / 差额调整；
 * 已忽略必须带处置人 / 时间且处置结果为 RECON_IGNORE。</p>
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】）。</p>
 *
 * @since V2
 */
public enum DifferenceStatus {

    /** 待处理：差异刚创建，等待处置。 */
    SETTLEMENT_DIFF_PENDING,

    /** 已处理：已完成处置（补记 / 冲正 / 差额调整）。 */
    SETTLEMENT_DIFF_HANDLED,

    /** 已忽略：确认差异无影响并说明，不生成分录。 */
    SETTLEMENT_DIFF_IGNORED;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
