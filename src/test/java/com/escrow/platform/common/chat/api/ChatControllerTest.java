package com.escrow.platform.common.chat.api;

import com.escrow.platform.common.chat.application.ChatConversationService;
import com.escrow.platform.common.chat.application.ChatMessageService;
import com.escrow.platform.common.chat.application.ChatUnreadService;
import com.escrow.platform.common.chat.domain.ChatConversationStatus;
import com.escrow.platform.common.chat.domain.ChatMessageType;
import com.escrow.platform.common.chat.domain.ChatScope;
import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 私聊接口契约测试（V11，FR-MSG.CHAT.01~.03，补遗 §4）。
 *
 * <p>只锁协议形状、参数夹紧与错误码透出；归属、归档、限流等业务守卫在对应 Service 单测。</p>
 *
 * @since V11
 */
@WebMvcTest(controllers = ChatController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class ChatControllerTest {

    private static final long CONVERSATION_ID = 9001L;
    private static final LoginUser WORKER =
            new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatConversationService conversationService;
    @MockitoBean
    private ChatMessageService messageService;
    @MockitoBean
    private ChatUnreadService unreadService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** FR-MSG.CHAT.01：开启咨询会话返回会话详情。 */
    @Test
    void createShouldReturnConversation() throws Exception {
        loginAs(WORKER);
        when(conversationService.createInquiry(any(), any())).thenReturn(conversationView());

        mockMvc.perform(post("/api/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":10,\"scope\":\"INQUIRY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(String.valueOf(CONVERSATION_ID)))
                .andExpect(jsonPath("$.data.scope").value("INQUIRY"))
                .andExpect(jsonPath("$.data.taskTitle").value("周末小区派单"));
    }

    /** FR-MSG.CHAT.01：列表按 scope 过滤，默认 page=1、size=20。 */
    @Test
    void listShouldPassScopeAndDefaultPaging() throws Exception {
        loginAs(WORKER);
        ConversationListItemView item = new ConversationListItemView(CONVERSATION_ID,
                ChatScope.INQUIRY, 10L, ChatConversationStatus.OPEN, LocalDateTime.now(), 3L);
        when(conversationService.list(any(), eq(ChatScope.INQUIRY), eq(1), eq(20)))
                .thenReturn(PageView.of(List.of(item), 1, 20, 1));

        mockMvc.perform(get("/api/conversations").param("scope", "INQUIRY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].unreadCount").value("3"))
                .andExpect(jsonPath("$.data.total").value("1"));
    }

    /** 非当事人：403 FORBIDDEN。 */
    @Test
    void detailOfForeignConversationShouldBeForbidden() throws Exception {
        loginAs(WORKER);
        when(conversationService.detail(any(), eq(CONVERSATION_ID)))
                .thenThrow(new ApiException(ErrorCode.FORBIDDEN, "无权访问该会话"));

        mockMvc.perform(get("/api/conversations/" + CONVERSATION_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** 未登录：401 UNAUTHENTICATED。 */
    @Test
    void detailWithoutPrincipalShouldReturn401() throws Exception {
        when(conversationService.detail(isNull(), eq(CONVERSATION_ID)))
                .thenThrow(new ApiException(ErrorCode.UNAUTHENTICATED, "请先登录"));

        mockMvc.perform(get("/api/conversations/" + CONVERSATION_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /** size=51 被夹到 50，不放大查询量。 */
    @Test
    void messagesShouldClampSizeToFifty() throws Exception {
        loginAs(WORKER);
        when(messageService.page(any(), eq(CONVERSATION_ID),
                eq(new MessagePageQuery(1, 50, null))))
                .thenReturn(PageView.of(List.of(messageView()), 1, 50, 1));

        mockMvc.perform(get("/api/conversations/" + CONVERSATION_ID + "/messages")
                        .param("size", "51"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].contactHint").value(false));

        verify(messageService).page(any(), eq(CONVERSATION_ID),
                eq(new MessagePageQuery(1, 50, null)));
    }

    /** FR-MSG.CHAT.02：发送成功返回消息视图。 */
    @Test
    void sendShouldReturnMessage() throws Exception {
        loginAs(WORKER);
        when(messageService.send(any(), eq(CONVERSATION_ID), any())).thenReturn(messageView());

        mockMvc.perform(post("/api/conversations/" + CONVERSATION_ID + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("你好"))
                .andExpect(jsonPath("$.data.msgType").value("TEXT"));
    }

    /** 空消息：400 INVALID_PARAMETER。 */
    @Test
    void sendBlankContentShouldBeInvalidParameter() throws Exception {
        loginAs(WORKER);
        when(messageService.send(any(), eq(CONVERSATION_ID), any()))
                .thenThrow(new ApiException(ErrorCode.INVALID_PARAMETER, "消息正文不能为空"));

        mockMvc.perform(post("/api/conversations/" + CONVERSATION_ID + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    /** 标记已读透传会话 ID。 */
    @Test
    void markReadShouldInvokeService() throws Exception {
        loginAs(WORKER);

        mockMvc.perform(post("/api/conversations/" + CONVERSATION_ID + "/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(unreadService).markRead(any(), eq(CONVERSATION_ID));
    }

    /** 未读总数返回计数（long 全局序列化为字符串）。 */
    @Test
    void unreadCountShouldReturnTotal() throws Exception {
        loginAs(WORKER);
        when(unreadService.unreadCount(any())).thenReturn(new UnreadCountView(7L));

        mockMvc.perform(get("/api/conversations/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value("7"));
    }

    /** 路径参数非数字：400 VALIDATION_FAILED。 */
    @Test
    void nonNumericPathShouldReturn400() throws Exception {
        loginAs(WORKER);

        mockMvc.perform(get("/api/conversations/abc/messages"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private static ConversationView conversationView() {
        return new ConversationView(CONVERSATION_ID, ChatScope.INQUIRY, 10L, "周末小区派单",
                1001L, 2001L, "雇主甲", "劳动者乙", ChatConversationStatus.OPEN,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private static MessageView messageView() {
        return new MessageView(7001L, CONVERSATION_ID, 1001L, ChatMessageType.TEXT,
                "你好", false, LocalDateTime.now());
    }

    private static void loginAs(LoginUser principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
