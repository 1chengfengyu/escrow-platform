package com.escrow.platform.fund.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 凭证事件类型（{@code fund_journal_batch.event_type}，v2-c5 定稿）。
 *
 * <p>一条批次只描述一个记账事件模板；分录方向与科目组合由事件决定，
 * 记账服务必须按模板生成分录，不允许调用方随意拼方向。</p>
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】）。</p>
 *
 * @since V2
 */
public enum JournalEventType {

    /** 发行总额增加（充值 / 测试账号初始化）：借 ACC_ISSUED，贷 ACC_AVAIL。 */
    ACCOUNT_ISSUE,

    /** 托管冻结：借 ACC_AVAIL，贷 ACC_FROZEN。 */
    ESCROW_HOLD,

    /** 托管退款：借 ACC_FROZEN，贷 ACC_AVAIL。 */
    ESCROW_REFUND,

    /** 验收释放：借 ACC_FROZEN，贷 ACC_TRANSIT。 */
    ESCROW_RELEASE,

    /** 零实付结算：借 ACC_FROZEN，贷 ACC_PLATFORM。 */
    SETTLE_ZERO_PAY,

    /** 打款成功结算：借 ACC_TRANSIT，贷 ACC_AVAIL + ACC_PLATFORM（按金额拆条）。 */
    PAYOUT_SETTLE,

    /** 对账补记：借 ACC_TRANSIT，贷 ACC_AVAIL + ACC_PLATFORM + ACC_AVAIL（雇主差额），V6。 */
    RECON_BOOK,

    /** 对账冲正：借 ACC_AVAIL（劳动者）+ ACC_PLATFORM + ACC_AVAIL（雇主），贷 ACC_TRANSIT，V6。 */
    RECON_REVERSE,

    /** 对账差额调整：借多记方，贷少记方（V6）。 */
    RECON_DIFF_ADJUST;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
