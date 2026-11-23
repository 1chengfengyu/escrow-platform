package com.escrow.platform.fund.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 凭证批次的业务类型（{@code fund_journal_batch.biz_type}，v2-c5 定稿）。
 *
 * <p>与 {@code biz_no} 组成幂等键 {@code uk_fund_journal_batch_biz}；
 * 同一业务动作重放必须复用同一个 {@code bizNo}。V2 只落地本卡用到的三个动作，
 * 其余候选（提现 / 结算 / 对账）等到各自版本再加。</p>
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】）。</p>
 *
 * @since V2
 */
public enum JournalBizType {

    /** 模拟充值：发行总额 → 用户可用余额。 */
    RECHARGE,

    /** 发布托管冻结：用户可用余额 → 用户冻结余额。 */
    ESCROW_HOLD,

    /** 撤单 / 到期退款：用户冻结余额 → 用户可用余额。 */
    ESCROW_REFUND,

    /** 验收释放：用户冻结余额 → 在途资金（FR-ESCROW.RELEASE.01）。 */
    ESCROW_RELEASE,

    /** 零实付结算：用户冻结余额 → 平台收入（FR-SETTLE.FEE.03）。 */
    SETTLE_ZERO_PAY,

    /** 打款成功结算：在途资金 → 劳动者可用 + 平台服务费（FR-PAY.CONFIRM.01）。 */
    PAYOUT_SETTLE,

    /** 对账调整：补记 / 冲正 / 差额调整（V6，FR-RECON.HANDLE.01）。 */
    RECON_ADJUST;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
