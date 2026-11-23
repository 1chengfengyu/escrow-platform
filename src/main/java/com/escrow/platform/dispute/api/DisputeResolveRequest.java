package com.escrow.platform.dispute.api;

import com.escrow.platform.dispute.domain.DisputeResolution;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理员裁决请求（V6，FR-DISPUTE.RESOLVE.01 / CLOSE.01）。
 *
 * @param resolution 裁决结论：RELEASE 放款 / REFUND 退款
 * @param basis      裁决依据，必填，≤500 字
 * @since V6
 */
public record DisputeResolveRequest(
        @NotNull(message = "resolution 不能为空") DisputeResolution resolution,
        @NotBlank(message = "basis 不能为空")
        @Size(max = 500, message = "basis 最长 500 字") String basis) {
}
