package com.escrow.platform.admin.api;

import com.escrow.platform.admin.application.AdminDashboardService;
import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 治理看板入口（V7，SRS 未单列，见 docs/decisions.md #51）。
 *
 * @since V7
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    /**
     * 汇总账号、任务与三张待办队列的计数。
     *
     * @param current 会话主体，必须是 ADMIN
     * @return 看板视图
     */
    @GetMapping
    public ApiResponse<AdminDashboardView> overview(@AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(adminDashboardService.overview(current));
    }
}
