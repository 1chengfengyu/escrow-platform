package com.escrow.platform.settlement.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 打款单状态（SRS A.3.5「打款单状态机」）。
 *
 * <p>{@code PAYOUT_PENDING_VERIFICATION} 就是「未知」的英文状态名，不再另设 PAYOUT_UNKNOWN。
 * {@code PAYOUT_ATTEMPT_LIMIT_REACHED} 是错误码不是状态，不许出现在本枚举里。</p>
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】）。</p>
 *
 * @since V2
 */
public enum PayoutStatus {

    /** 待执行：打款请求已创建，尚未下发通道。 */
    PAYOUT_PENDING_EXECUTE,

    /** 执行中：打款请求已下发通道，等待应答。 */
    PAYOUT_EXECUTING,

    /** 成功：通道确认已出款，库层强制有 actual_amount。 */
    PAYOUT_SUCCESS,

    /** 失败：通道明确拒绝。 */
    PAYOUT_FAIL,

    /** 未知（待查证）：超时 / 应答丢失，需要查证。 */
    PAYOUT_PENDING_VERIFICATION,

    /** 待人工处理：查证无法自动判定，转人工。 */
    PAYOUT_MANUAL_REVIEW;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
