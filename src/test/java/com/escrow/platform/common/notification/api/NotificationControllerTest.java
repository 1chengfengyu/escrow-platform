package com.escrow.platform.common.notification.api;

import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.notification.NotificationQueryService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 站内信接口契约测试（V7，FR-NOTIFY.INBOX.01）。
 *
 * @since V7
 */
@WebMvcTest(controllers = NotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class NotificationControllerTest {

    private static final LoginUser WORKER =
            new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationQueryService notificationQueryService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 列表：已读标记为布尔值。 */
    @Test
    void listShouldReturnInbox() throws Exception {
        loginAs(WORKER);
        NotificationView view = new NotificationView(1L, "PAYOUT", "P001", "打款成功",
                "已到账", false, LocalDateTime.of(2026, 11, 18, 9, 0));
        when(notificationQueryService.list(any(), eq(0), eq(1), eq(20)))
                .thenReturn(PageView.of(List.of(view), 1, 20, 1));

        mockMvc.perform(get("/api/notifications")
                        .param("readFlag", "0")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].read").value(false))
                .andExpect(jsonPath("$.data.items[0].bizNo").value("P001"))
                .andExpect(jsonPath("$.data.total").value("1"));
    }

    /** 全部已读：返回实际条数。 */
    @Test
    void markAllReadShouldReturnCount() throws Exception {
        loginAs(WORKER);
        when(notificationQueryService.markAllRead(any())).thenReturn(3);

        mockMvc.perform(post("/api/notifications/read-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(3));
    }

    /** 未读数：返回计数。 */
    @Test
    void unreadCountShouldReturnCount() throws Exception {
        loginAs(WORKER);
        when(notificationQueryService.unreadCount(any())).thenReturn(7L);

        mockMvc.perform(get("/api/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value("7"));
    }

    /** 单条已读：透传 ID。 */
    @Test
    void markReadShouldPassId() throws Exception {
        loginAs(WORKER);

        mockMvc.perform(post("/api/notifications/1/read"))
                .andExpect(status().isOk());

        verify(notificationQueryService).markRead(any(), eq(1L));
    }

    private static void loginAs(LoginUser principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
