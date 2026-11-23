package com.escrow.platform.fund.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 资金科目（SRS 3.4.1「六科目」）。
 *
 * <p>归属由 {@code ck_fund_account_subject} 钉死：USER 只能有 ACC_AVAIL / ACC_FROZEN，
 * SYSTEM 只能有后四个全局科目。{@code ACC_ISSUED} 是全局唯一的已发放科目，不许按用户拆分，
 * 否则 SRS 3.4.1 的恒等式永远不平。</p>
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】）。</p>
 *
 * @since V2
 */
public enum AccountSubject {

    /** 可用余额：个人用户持有。 */
    ACC_AVAIL,

    /** 冻结余额（托管在途）：个人用户持有。 */
    ACC_FROZEN,

    /** 平台收入：系统账户持有。 */
    ACC_PLATFORM,

    /** 在途资金：系统账户持有，对账前的过账科目。 */
    ACC_TRANSIT,

    /** 已提现：系统账户持有。 */
    ACC_WITHDRAWN,

    /** 已发放：系统账户持有，全局唯一，禁止按用户拆分。 */
    ACC_ISSUED;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
