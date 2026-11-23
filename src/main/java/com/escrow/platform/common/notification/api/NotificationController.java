package com.escrow.platform.common.notification.api;

import com.escrow.platform.common.notification.NotificationQueryService;
import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.common.web.PageView;
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
 * 站内信入口（V7，FR-MSG.LIST.01）。
 *
 * <p>四个端点都只作用于当前会话的消息，归属校验在
 * {@link NotificationQueryService}，控制器不接收 userId 参数。</p>
 *
 * @since V7
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    /**
     * 分页查询站内信。
     *
     * @param readFlag 0 未读 / 1 已读，缺省全部
     * @param page     页码，从 1 开始
     * @param size     每页条数
     * @param current  会话主体
     * @return 分页结果
     */
    @GetMapping
    public ApiResponse<PageView<NotificationView>> list(
            @RequestParam(required = false) Integer readFlag,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(notificationQueryService.list(current, readFlag, page, size));
    }

    /**
     * 标记单条已读（幂等）。
     *
     * @param id      站内信 ID
     * @param current 会话主体
     */
    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id,
                                      @AuthenticationPrincipal LoginUser current) {
        notificationQueryService.markRead(current, id);
        return ApiResponse.ok(null);
    }

    /**
     * 全部标记已读。
     *
     * @param current 会话主体
     * @return 本次实际标记条数
     */
    @PostMapping("/read-all")
    public ApiResponse<ReadAllView> markAllRead(@AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(new ReadAllView(notificationQueryService.markAllRead(current)));
    }

    /**
     * 未读条数。
     *
     * @param current 会话主体
     * @return 未读条数
     */
    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountView> unreadCount(@AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(new UnreadCountView(notificationQueryService.unreadCount(current)));
    }
}
