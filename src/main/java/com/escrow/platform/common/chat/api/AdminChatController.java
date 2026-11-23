package com.escrow.platform.common.chat.api;

import com.escrow.platform.common.chat.application.ChatAdminQueryService;
import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端会话取证入口（V11，补遗 §5.3）。
 *
 * <p>只提供只读分页：没有发消息、删消息、归档等写操作；权限与审计在
 * {@link ChatAdminQueryService}，控制器只做分页参数夹紧。</p>
 *
 * @since V11
 */
@RestController
@RequestMapping("/api/admin/conversations")
@RequiredArgsConstructor
public class AdminChatController {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ChatAdminQueryService chatAdminQueryService;

    /**
     * 管理员按会话读取消息（每次查询写一条取证审计）。
     *
     * @param current 当前登录主体
     * @param id      会话 ID
     * @param page    页码，从 1 开始
     * @param size    每页条数，夹到 1~50
     * @return 消息分页视图
     */
    @GetMapping("/{id}/messages")
    public ApiResponse<PageView<MessageView>> messages(
            @AuthenticationPrincipal LoginUser current,
            @PathVariable Long id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(chatAdminQueryService.pageMessages(
                current, id, pageOf(page), sizeOf(size)));
    }

    private static int pageOf(Integer page) {
        return page == null ? 1 : Math.max(1, page);
    }

    private static int sizeOf(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.max(1, Math.min(MAX_PAGE_SIZE, size));
    }
}
