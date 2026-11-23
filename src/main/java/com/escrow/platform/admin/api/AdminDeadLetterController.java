package com.escrow.platform.admin.api;

import com.escrow.platform.common.notification.NotificationDeadLetterAdminService;
import com.escrow.platform.common.notification.api.DeadLetterView;
import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端死信入口（V8，FR-MSG.DLT.01）。
 *
 * <p>URL 属于 D7 治理面，实现全部在 common 的
 * {@link NotificationDeadLetterAdminService}；控制器只做协议转换，ADMIN 校验在 application 层。</p>
 *
 * @since V8
 */
@RestController
@RequestMapping("/api/admin/notifications/dead-letters")
@RequiredArgsConstructor
public class AdminDeadLetterController {

    private final NotificationDeadLetterAdminService notificationDeadLetterAdminService;

    /**
     * 死信列表，按落表时间倒序。
     *
     * @param current 会话主体，必须是 ADMIN
     * @param limit   条数上限（1~100）
     * @return 死信视图列表
     */
    @GetMapping
    public ApiResponse<List<DeadLetterView>> list(
            @AuthenticationPrincipal LoginUser current,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(notificationDeadLetterAdminService.list(current, limit));
    }

    /**
     * 重放一条死信。
     *
     * @param current 会话主体，必须是 ADMIN
     * @param id      死信 ID
     * @return 被重放的死信视图
     */
    @PostMapping("/{id}/replay")
    public ApiResponse<DeadLetterView> replay(@AuthenticationPrincipal LoginUser current,
                                              @PathVariable Long id) {
        return ApiResponse.ok(notificationDeadLetterAdminService.replay(current, id));
    }
}
