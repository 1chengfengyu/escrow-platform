package com.escrow.platform.admin.api;

import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.api.AdminUserStatusRequest;
import com.escrow.platform.identity.api.AdminUserView;
import com.escrow.platform.identity.application.AdminUserService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端账号接口契约测试（V7，FR-ADMIN.USER.01 / .02）。
 *
 * @since V7
 */
@WebMvcTest(controllers = AdminUserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class AdminUserControllerTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminUserService adminUserService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 列表：分页字段与总数为字符串（JacksonConfig 的全局契约）。 */
    @Test
    void listShouldReturnPage() throws Exception {
        loginAs(ADMIN);
        AdminUserView view = new AdminUserView(1001L, "张三", "13800138000",
                "worker@example.com", "WORKER", "ACTIVE", LocalDateTime.of(2026, 11, 18, 10, 0));
        when(adminUserService.list(any(), eq("张"), eq("ACTIVE"), eq(1), eq(10)))
                .thenReturn(PageView.of(List.of(view), 1, 10, 1));

        mockMvc.perform(get("/api/admin/users")
                        .param("keyword", "张")
                        .param("status", "ACTIVE")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].userId").value("1001"))
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.total").value("1"));
    }

    /** 禁用：请求体原因透传到 application 层。 */
    @Test
    void disableShouldPassReason() throws Exception {
        loginAs(ADMIN);
        when(adminUserService.disable(any(), eq(1001L), any(AdminUserStatusRequest.class)))
                .thenReturn(new AdminUserView(1001L, "张三", "13800138000",
                        "worker@example.com", "WORKER", "DISABLED", LocalDateTime.now()));

        mockMvc.perform(post("/api/admin/users/1001/disable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"发布违规任务\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        verify(adminUserService).disable(any(), eq(1001L), eq(new AdminUserStatusRequest("发布违规任务")));
    }

    /** 非管理员：403 + FORBIDDEN。 */
    @Test
    void nonAdminShouldBeForbidden() throws Exception {
        loginAs(new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER));
        when(adminUserService.enable(any(), eq(1001L), any(AdminUserStatusRequest.class)))
                .thenThrow(new ApiException(ErrorCode.FORBIDDEN, "仅管理员可管理账号"));

        mockMvc.perform(post("/api/admin/users/1001/enable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"误封恢复\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private static void loginAs(LoginUser principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
