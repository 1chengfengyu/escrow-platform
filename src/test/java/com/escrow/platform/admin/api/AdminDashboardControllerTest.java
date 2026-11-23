package com.escrow.platform.admin.api;

import com.escrow.platform.admin.application.AdminDashboardService;
import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 治理看板接口契约测试（V7，FR-ADMIN.DASH.01）。
 *
 * @since V7
 */
@WebMvcTest(controllers = AdminDashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class AdminDashboardControllerTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminDashboardService adminDashboardService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 计数按全局 Long→字符串契约输出。 */
    @Test
    void overviewShouldReturnCounts() throws Exception {
        loginAs(ADMIN);
        when(adminDashboardService.overview(any()))
                .thenReturn(new AdminDashboardView(12L, 34L, 3L, 2L, 1L));

        mockMvc.perform(get("/api/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userCount").value("12"))
                .andExpect(jsonPath("$.data.taskCount").value("34"))
                .andExpect(jsonPath("$.data.pendingPayoutCount").value("3"))
                .andExpect(jsonPath("$.data.pendingDisputeCount").value("2"))
                .andExpect(jsonPath("$.data.pendingDifferenceCount").value("1"));
    }

    /** 非管理员：403。 */
    @Test
    void nonAdminShouldBeForbidden() throws Exception {
        loginAs(new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER));
        when(adminDashboardService.overview(any()))
                .thenThrow(new ApiException(ErrorCode.FORBIDDEN, "仅管理员可查看看板"));

        mockMvc.perform(get("/api/admin/dashboard"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private static void loginAs(LoginUser principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
