package com.escrow.platform.dispute.api;

import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.dispute.application.DisputeResolutionService;
import com.escrow.platform.dispute.application.DisputeService;
import com.escrow.platform.dispute.application.DisputeTxService;
import com.escrow.platform.dispute.domain.DisputeStatus;
import com.escrow.platform.identity.domain.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 争议管理端（V6）：待裁决列表、超期统计、裁决。
 *
 * <p>管理员校验在应用层（{@code DisputeService.isAdmin}），与打款处置台同一口径。</p>
 *
 * @since V6
 */
@RestController
@RequestMapping("/api/admin/disputes")
@RequiredArgsConstructor
public class AdminDisputeController {

    private final DisputeService disputeService;
    private final DisputeResolutionService disputeResolutionService;

    /**
     * 争议列表。
     *
     * @param current 会话主体
     * @param status  状态过滤（可空）
     * @return 列表
     */
    @GetMapping
    public ApiResponse<List<DisputeSummaryView>> list(
            @AuthenticationPrincipal LoginUser current,
            @RequestParam(required = false) DisputeStatus status) {
        return ApiResponse.ok(disputeService.listForAdmin(current, status));
    }

    /**
     * 超期待裁决统计（FR-DISPUTE.CLOSE.02）。
     *
     * @param current 会话主体
     * @param days    超期天数阈值，默认 7
     * @return 超期列表，最长等待在前
     */
    @GetMapping("/overdue")
    public ApiResponse<List<DisputeSummaryView>> overdue(
            @AuthenticationPrincipal LoginUser current,
            @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(disputeService.overdue(current, days));
    }

    /**
     * 争议详情（含双方证据）。
     *
     * @param current   会话主体
     * @param disputeId 争议单 ID
     * @return 详情
     */
    @GetMapping("/{disputeId}")
    public ApiResponse<DisputeView> detail(@AuthenticationPrincipal LoginUser current,
                                           @PathVariable Long disputeId) {
        return ApiResponse.ok(disputeService.detail(current, disputeId));
    }

    /**
     * 裁决放款或退款（FR-DISPUTE.RESOLVE.01）。
     *
     * @param current   会话主体
     * @param disputeId 争议单 ID
     * @param request   裁决请求
     * @return 裁决结果；放款时带打款单 ID
     */
    @PostMapping("/{disputeId}/resolve")
    public ApiResponse<DisputeTxService.ResolutionOutcome> resolve(
            @AuthenticationPrincipal LoginUser current,
            @PathVariable Long disputeId,
            @Valid @RequestBody DisputeResolveRequest request) {
        return ApiResponse.ok(disputeResolutionService.resolve(current, disputeId,
                request.resolution(), request.basis()));
    }
}
