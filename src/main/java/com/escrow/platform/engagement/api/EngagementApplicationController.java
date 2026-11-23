package com.escrow.platform.engagement.api;

import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.engagement.application.ApplicationService;
import com.escrow.platform.engagement.application.HireService;
import com.escrow.platform.engagement.domain.ApplicationPageQuery;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报名与录用入口（FR-SIGN.APPLY.01~.08、FR-SIGN.HIRE.01~.04）。
 *
 * <p>Controller 只做三件事：取会话主体、转交 application 层、包 {@link ApiResponse}。
 * 身份与归属判定、分页解析全部在 Service / 领域纯函数；端点不在
 * {@code SecurityConfig.PUBLIC_PATHS} 里，未登录一律 401。</p>
 *
 * @since V3
 */
@RestController
@RequestMapping("/api/tasks/{taskId}/applications")
@RequiredArgsConstructor
public class EngagementApplicationController {

    private final ApplicationService applicationService;
    private final HireService hireService;

    /** 劳动者报名：200 + {@code data.id}（字符串化的雪花 ID）。 */
    @PostMapping
    public ApiResponse<ApplicationCreatedView> apply(@PathVariable Long taskId,
                                                     @AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(new ApplicationCreatedView(applicationService.apply(current, taskId)));
    }

    /** 雇主录用报名者：200 + {@code data.contractId}（重复录用返回同一签约 ID）。 */
    @PostMapping("/{applicationId}/hire")
    public ApiResponse<HireResultView> hire(@PathVariable Long taskId,
                                            @PathVariable Long applicationId,
                                            @AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(new HireResultView(
                hireService.hire(current, taskId, applicationId)));
    }

    /** 雇主查看本任务报名者：200 + 分页视图（报名时间倒序）。 */
    @GetMapping
    public ApiResponse<PageView<ApplicationListItemView>> list(
            @PathVariable Long taskId,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size,
            @AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(applicationService.list(current, taskId,
                ApplicationPageQuery.from(page, size)));
    }
}
