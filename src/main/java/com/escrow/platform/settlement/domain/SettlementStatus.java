package com.escrow.platform.settlement.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 结算单状态（SRS A.3.4「结算单状态机」）。
 *
 * <p>结算单没有「待人工处理」语义，该状态只在打款单上（{@code PAYOUT_MANUAL_REVIEW}）。</p>
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】）。</p>
 *
 * @since V2
 */
public enum SettlementStatus {

    /** 待生成：名额验收合格，结算单由 v2-c4 服务层生成。 */
    SETTLEMENT_PENDING_GENERATE,

    /** 已确认：结算单已生成、金额与费率快照已固化。 */
    SETTLEMENT_CONFIRMED,

    /** 打款中：已下发打款请求，等待通道应答。 */
    SETTLEMENT_PAYING,

    /** 待重试：上一次打款失败，等待下一次重试。 */
    SETTLEMENT_PENDING_RETRY,

    /** 已完结：打款成功或结算作废。 */
    SETTLEMENT_COMPLETED,

    /** 已作废：结算单被撤销（如任务取消、争议裁定退款）。 */
    SETTLEMENT_VOIDED;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
