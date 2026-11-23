package com.escrow.platform.common.chat.api;

import com.escrow.platform.common.chat.application.ChatConversationService;
import com.escrow.platform.common.chat.application.ChatMessageService;
import com.escrow.platform.common.chat.application.ChatUnreadService;
import com.escrow.platform.common.chat.domain.ChatScope;
import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.common.web.PageView;
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
 * 私聊入口（V11，FR-MSG.CHAT.01~.03，补遗 §4）。
 *
 * <p>控制器只做协议转换与分页参数夹紧：归属校验、状态守卫与幂等都在
 * {@link ChatConversationService} / {@link ChatMessageService} / {@link ChatUnreadService}，
 * <b>不接收 {@code userId} 参数</b>，当前用户只从会话取。</p>
 *
 * @since V11
 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ChatController {

    /** 单页条数上限，与补遗 §2.3 一致；前端传更大值直接夹紧，不报错。 */
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ChatConversationService conversationService;
    private final ChatMessageService messageService;
    private final ChatUnreadService unreadService;

    /**
     * 开启或取回咨询会话（FR-MSG.CHAT.01）。
     *
     * @param current 会话主体
     * @param request 任务 ID 与可选目标劳动者
     * @return 会话详情；重复发起返回既有会话
     */
    @PostMapping
    public ApiResponse<ConversationView> create(
            @AuthenticationPrincipal LoginUser current,
            @RequestBody(required = false) CreateConversationRequest request) {
        return ApiResponse.ok(conversationService.createInquiry(current, request));
    }

    /**
     * 我的会话列表（按当前身份过滤）。
     *
     * @param current 会话主体
     * @param scope   可选范围过滤
     * @param page    页码，从 1 开始
     * @param size    每页条数，夹到 1~50
     * @return 会话分页视图
     */
    @GetMapping
    public ApiResponse<PageView<ConversationListItemView>> list(
            @AuthenticationPrincipal LoginUser current,
            @RequestParam(required = false) ChatScope scope,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(conversationService.list(current, scope, pageOf(page), sizeOf(size)));
    }

    /**
     * 未读总数（导航角标）。
     *
     * <p>必须声明在 {@code /{id}} 之前便于阅读；路由匹配以字面量优先，不会与详情冲突。</p>
     *
     * @param current 会话主体
     * @return 未读总数
     */
    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountView> unreadCount(@AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(unreadService.unreadCount(current));
    }

    /**
     * 会话详情（含任务标题与双方公开昵称）。
     *
     * @param current 会话主体
     * @param id      会话 ID
     * @return 会话详情
     */
    @GetMapping("/{id}")
    public ApiResponse<ConversationView> detail(@AuthenticationPrincipal LoginUser current,
                                                @PathVariable Long id) {
        return ApiResponse.ok(conversationService.detail(current, id));
    }

    /**
     * 历史消息正序分页；带 {@code afterId} 时走增量轮询。
     *
     * @param current 会话主体
     * @param id      会话 ID
     * @param page    页码，从 1 开始
     * @param size    每页条数，夹到 1~50
     * @param afterId 增量游标，可空
     * @return 消息分页视图
     */
    @GetMapping("/{id}/messages")
    public ApiResponse<PageView<MessageView>> messages(
            @AuthenticationPrincipal LoginUser current,
            @PathVariable Long id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long afterId) {
        MessagePageQuery query = new MessagePageQuery(pageOf(page), sizeOf(size), afterId);
        return ApiResponse.ok(messageService.page(current, id, query));
    }

    /**
     * 发送一条文字消息（FR-MSG.CHAT.02）。
     *
     * @param current 会话主体
     * @param id      会话 ID
     * @param request 正文与客户端消息 ID
     * @return 已落库的消息视图
     */
    @PostMapping("/{id}/messages")
    public ApiResponse<MessageView> send(
            @AuthenticationPrincipal LoginUser current,
            @PathVariable Long id,
            @RequestBody(required = false) SendMessageRequest request) {
        return ApiResponse.ok(messageService.send(current, id, request));
    }

    /**
     * 标记整个会话已读（幂等）。
     *
     * @param current 会话主体
     * @param id      会话 ID
     * @return 成功响应
     */
    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@AuthenticationPrincipal LoginUser current,
                                      @PathVariable Long id) {
        unreadService.markRead(current, id);
        return ApiResponse.ok(null);
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
