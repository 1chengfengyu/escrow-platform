package com.escrow.platform.common.chat.api;

import com.escrow.platform.common.chat.application.ChatAdminQueryService;
import com.escrow.platform.common.chat.domain.ChatMessageType;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端会话取证接口契约测试（V11，补遗 §5.3）。
 *
 * @since V11
 */
@WebMvcTest(controllers = AdminChatController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class AdminChatControllerTest {

    private static final long CONVERSATION_ID = 9001L;
    private static final LoginUser ADMIN =
            new LoginUser(9000L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);
    private static final LoginUser WORKER =
            new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatAdminQueryService chatAdminQueryService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 只读分页成功：返回消息列表并透传默认分页。 */
    @Test
    void messagesShouldReturnPage() throws Exception {
        loginAs(ADMIN);
        when(chatAdminQueryService.pageMessages(any(), eq(CONVERSATION_ID), eq(1), eq(20)))
                .thenReturn(PageView.of(List.of(new MessageView(7001L, CONVERSATION_ID, 2001L,
                        ChatMessageType.TEXT, "交付说明", false, LocalDateTime.now())), 1, 20, 1));

        mockMvc.perform(get("/api/admin/conversations/" + CONVERSATION_ID + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("7001"))
                .andExpect(jsonPath("$.data.items[0].content").value("交付说明"))
                .andExpect(jsonPath("$.data.total").value("1"));
    }

    /** size 超上限被夹到 50。 */
    @Test
    void sizeShouldBeClampedToFifty() throws Exception {
        loginAs(ADMIN);
        when(chatAdminQueryService.pageMessages(any(), eq(CONVERSATION_ID), eq(2), eq(50)))
                .thenReturn(PageView.of(List.of(), 2, 50, 0));

        mockMvc.perform(get("/api/admin/conversations/" + CONVERSATION_ID + "/messages")
                        .param("page", "2")
                        .param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        verify(chatAdminQueryService).pageMessages(any(), eq(CONVERSATION_ID), eq(2), eq(50));
    }

    /** 非管理员：403 FORBIDDEN。 */
    @Test
    void nonAdminShouldBeForbidden() throws Exception {
        loginAs(WORKER);
        when(chatAdminQueryService.pageMessages(any(), eq(CONVERSATION_ID), any(Integer.class),
                any(Integer.class)))
                .thenThrow(new ApiException(ErrorCode.FORBIDDEN, "仅管理员可查看会话"));

        mockMvc.perform(get("/api/admin/conversations/" + CONVERSATION_ID + "/messages"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** 会话不存在：404 RESOURCE_NOT_FOUND。 */
    @Test
    void missingConversationShouldBe404() throws Exception {
        loginAs(ADMIN);
        when(chatAdminQueryService.pageMessages(any(), eq(CONVERSATION_ID), any(Integer.class),
                any(Integer.class)))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在"));

        mockMvc.perform(get("/api/admin/conversations/" + CONVERSATION_ID + "/messages"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private static void loginAs(LoginUser principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
