package com.escrow.platform.settlement.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 差异处置结果（settlement_difference.resolution）。
 *
 * <p>前三种生成分录，{@code RECON_IGNORE} 不生成任何分录（不得造 0 元或占位分录）。
 * 与 {@code ck_settlement_difference_resolved} 配合：已处理只允许前三种，已忽略只允许 RECON_IGNORE。</p>
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】）。</p>
 *
 * @since V2
 */
public enum DifferenceResolution {

    /** 补记：通道已出款但本地无记录，生成分录补账。 */
    RECON_BOOK,

    /** 冲正：本地已出款但通道无记录，生成分录冲回。 */
    RECON_REVERSE,

    /** 差额调整：本地与通道金额不一致，生成分录调差额。 */
    RECON_ADJUST,

    /** 忽略并说明：差异确认无影响，不生成任何分录。 */
    RECON_IGNORE;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
