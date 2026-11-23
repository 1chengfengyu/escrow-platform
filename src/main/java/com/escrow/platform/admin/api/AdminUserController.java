package com.escrow.platform.admin.api;

import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.api.AdminUserStatusRequest;
import com.escrow.platform.identity.api.AdminUserView;
import com.escrow.platform.identity.application.AdminUserService;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端账号治理入口（V7，FR-ADMIN.USER.01）。
 *
 * <p>URL 属于 D7 治理面，实现全部在 identity 域的 {@link AdminUserService}；
 * 控制器只做协议转换，ADMIN 校验在 application 层。</p>
 *
 * @since V7
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    /**
     * 分页查询账号，支持昵称 / 手机号 / 邮箱模糊匹配与状态过滤。
     *
     * @param current 会话主体，必须是 ADMIN
     * @param keyword 模糊关键字，可空
     * @param status  状态过滤，可空
     * @param page    页码，从 1 开始
     * @param size    每页条数
     * @return 分页结果
     */
    @GetMapping
    public ApiResponse<PageView<AdminUserView>> list(
            @AuthenticationPrincipal LoginUser current,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminUserService.list(current, keyword, status, page, size));
    }

    /**
     * 禁用账号。
     *
     * @param userId  目标账号 ID
     * @param request 含必填原因
     * @param current 会话主体，必须是 ADMIN
     * @return 变更后的账号视图
     */
    @PostMapping("/{userId}/disable")
    public ApiResponse<AdminUserView> disable(@PathVariable Long userId,
                                              @RequestBody AdminUserStatusRequest request,
                                              @AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(adminUserService.disable(current, userId, request));
    }

    /**
     * 启用账号。
     *
     * @param userId  目标账号 ID
     * @param request 含必填原因
     * @param current 会话主体，必须是 ADMIN
     * @return 变更后的账号视图
     */
    @PostMapping("/{userId}/enable")
    public ApiResponse<AdminUserView> enable(@PathVariable Long userId,
                                             @RequestBody AdminUserStatusRequest request,
                                             @AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(adminUserService.enable(current, userId, request));
    }
}
