package com.escrow.platform.settlement.api;

import com.escrow.platform.settlement.domain.PayoutManualDecisionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 人工处置请求（V5 处置台）。
 *
 * <p>{@code actualAmount} 的必填性按处置动作区分，不能一概 {@code @NotNull}：
 * 转差异不需要金额；零扣款固定为 0；只有「按实际金额」必须带。</p>
 *
 * @param decision     处置动作
 * @param actualAmount 实际金额（分）
 * @param remark       处置说明 / 零扣款凭据编号
 * @since V5
 */
public record PayoutDecisionRequest(
        @NotNull(message = "decision 不能为空") PayoutManualDecisionType decision,
        Long actualAmount,
        @Size(max = 500, message = "remark 最长 500 字") String remark) {
}
