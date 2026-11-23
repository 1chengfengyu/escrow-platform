package com.escrow.platform.settlement.api;

import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.settlement.application.PayoutAdminService;
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
 * 打款处置台（V5，FR-PAY.MANUAL.01~.03 / FR-JOB.AGING.01）。
 *
 * <p>Controller 只做参数解析与转发，权限与状态校验全在 {@link PayoutAdminService}。</p>
 *
 * @since V5
 */
@RestController
@RequestMapping("/api/admin/payouts")
@RequiredArgsConstructor
public class AdminPayoutController {

    private final PayoutAdminService payoutAdminService;

    /**
     * 打款单列表。
     *
     * @param current 当前登录用户
     * @param status  状态过滤（可空）
     * @param page    页码
     * @param size    每页条数
     * @return 分页结果
     */
    @GetMapping
    public ApiResponse<PageView<PayoutAdminView>> list(
            @AuthenticationPrincipal LoginUser current,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(payoutAdminService.list(current, status, page, size));
    }

    /**
     * 账龄告警。
     *
     * @param current 当前登录用户
     * @return 告警列表
     */
    @GetMapping("/aging")
    public ApiResponse<List<PayoutAgingAlert>> aging(@AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(payoutAdminService.aging(current));
    }

    /**
     * 人工处置三选一。
     *
     * @param current  当前登录用户
     * @param payoutId 打款单 ID
     * @param request  处置请求
     * @return 处置后的打款单
     */
    @PostMapping("/{payoutId}/decision")
    public ApiResponse<PayoutAdminView> decide(@AuthenticationPrincipal LoginUser current,
                                               @PathVariable Long payoutId,
                                               @Valid @RequestBody PayoutDecisionRequest request) {
        return ApiResponse.ok(payoutAdminService.decide(current, payoutId, request));
    }
}
