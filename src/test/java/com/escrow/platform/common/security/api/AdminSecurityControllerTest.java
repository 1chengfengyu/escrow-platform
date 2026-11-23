package com.escrow.platform.common.security.api;

import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.security.application.SecurityEventQueryService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全事件查询接口契约测试（V7）。
 *
 * @since V7
 */
@WebMvcTest(controllers = AdminSecurityController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class AdminSecurityControllerTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SecurityEventQueryService securityEventQueryService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(LoginUser user) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    /** 按账号与时间检索。 */
    @Test
    void eventsShouldReturnPage() throws Exception {
        loginAs(ADMIN);
        SecurityEventView view = new SecurityEventView(1L, 1001L, "WORKER", "GET",
                "/api/admin/disputes", "无权查看", "dispute", 60L, "10.0.0.8", LocalDateTime.now());
        when(securityEventQueryService.list(any(), eq(1001L), any(), any(), eq(1), eq(20)))
                .thenReturn(PageView.of(List.of(view), 1, 20, 1));

        mockMvc.perform(get("/api/admin/security/events").param("userId", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].reason").value("无权查看"))
                .andExpect(jsonPath("$.data.total").value("1"));
    }
}
