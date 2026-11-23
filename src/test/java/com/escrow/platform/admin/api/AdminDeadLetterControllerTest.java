package com.escrow.platform.admin.api;

import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.notification.NotificationDeadLetterAdminService;
import com.escrow.platform.common.notification.api.DeadLetterView;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端死信接口契约测试（V8，FR-MSG.DLT.01）。
 *
 * @since V8
 */
@WebMvcTest(controllers = AdminDeadLetterController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class AdminDeadLetterControllerTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationDeadLetterAdminService notificationDeadLetterAdminService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(LoginUser user) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    /** 列表返回死信字段。 */
    @Test
    void listShouldReturnDeadLetters() throws Exception {
        loginAs(ADMIN);
        when(notificationDeadLetterAdminService.list(any(), eq(5))).thenReturn(List.of(
                new DeadLetterView(31L, "escrow-notify.DLT", "not-json", "消费失败",
                        0, null, LocalDateTime.now())));

        mockMvc.perform(get("/api/admin/notifications/dead-letters").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(31))
                .andExpect(jsonPath("$.data[0].topic").value("escrow-notify.DLT"));
    }

    /** 重放成功返回 200。 */
    @Test
    void replayShouldReturnOk() throws Exception {
        loginAs(ADMIN);
        when(notificationDeadLetterAdminService.replay(any(), eq(31L))).thenReturn(
                new DeadLetterView(31L, "escrow-notify.DLT", "{\"eventId\":\"evt-1\"}", "消费失败",
                        1, LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(post("/api/admin/notifications/dead-letters/31/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(31));
    }

    /** 非管理员：403。 */
    @Test
    void listByNonAdminShouldBeForbidden() throws Exception {
        loginAs(new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER));
        when(notificationDeadLetterAdminService.list(any(), eq(20)))
                .thenThrow(new ApiException(ErrorCode.FORBIDDEN, "仅管理员可操作死信"));

        mockMvc.perform(get("/api/admin/notifications/dead-letters"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** 原文不可解析：400。 */
    @Test
    void replayIllegalPayloadShouldBeBadRequest() throws Exception {
        loginAs(ADMIN);
        when(notificationDeadLetterAdminService.replay(any(), eq(31L)))
                .thenThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "死信原文不是合法的通知消息，无法重放"));

        mockMvc.perform(post("/api/admin/notifications/dead-letters/31/replay"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
