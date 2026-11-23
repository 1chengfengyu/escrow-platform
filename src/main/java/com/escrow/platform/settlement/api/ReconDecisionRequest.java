package com.escrow.platform.settlement.api;

import com.escrow.platform.settlement.domain.DifferenceResolution;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 对账差异处置请求（V6，FR-RECON.HANDLE.01）。
 *
 * <p>处置动作与差异类型强绑定：补记只对 {@code LOST_RESPONSE}、冲正只对
 * {@code CHANNEL_MISSING}、差额调整只对 {@code AMOUNT_MISMATCH}，
 * {@code RESULT_UNDETERMINED} 只能忽略。绑定关系在服务层校验，不在 DTO 上做。</p>
 *
 * @param resolution 处置动作
 * @param remark     处置说明；忽略差异时必填（忽略原因只入日志，表结构无备注列）
 * @since V6
 */
public record ReconDecisionRequest(
        @NotNull(message = "resolution 不能为空") DifferenceResolution resolution,
        @Size(max = 500, message = "remark 最长 500 字") String remark) {
}
