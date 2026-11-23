package com.escrow.platform.engagement.api;

import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.RestAccessDeniedHandler;
import com.escrow.platform.common.security.RestAuthenticationEntryPoint;
import com.escrow.platform.common.security.SecurityConfig;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.common.web.TraceIdFilter;
import com.escrow.platform.engagement.application.ApplicationService;
import com.escrow.platform.engagement.application.HireService;
import com.escrow.platform.engagement.domain.ApplicationPageQuery;
import com.escrow.platform.engagement.domain.ApplicationStatus;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 报名接口契约测试（FR-SIGN.APPLY.01~.07）。
 *
 * <p>切片只加载 {@link EngagementApplicationController}，Service 用
 * {@code @MockitoBean} 隔离；分页参数解析走真实的 {@code ApplicationPageQuery}。</p>
 *
 * @since V3
 */
@WebMvcTest(controllers = EngagementApplicationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        TraceIdFilter.class, JacksonConfig.class})
class EngagementApplicationControllerTest {

    private static final LoginUser WORKER =
            new LoginUser(2001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final LoginUser EMPLOYER =
            new LoginUser(1001L, "employer@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApplicationService applicationService;

    @MockitoBean
    private HireService hireService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 报名成功：200 + data.id 字符串化。 */
    @Test
    void workerApplyShouldReturnStringId() throws Exception {
        loginAs(WORKER);
        when(applicationService.apply(WORKER, 10L)).thenReturn(1234567890123456789L);

        mockMvc.perform(post("/api/tasks/{taskId}/applications", 10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value("1234567890123456789"));
    }

    /** 重复报名：409 + ALREADY_APPLIED。 */
    @Test
    void applyShouldReturnConflictWhenAlreadyApplied() throws Exception {
        loginAs(WORKER);
        doThrow(new ApiException(ErrorCode.ALREADY_APPLIED, "已报名该任务，请勿重复提交"))
                .when(applicationService).apply(WORKER, 10L);

        mockMvc.perform(post("/api/tasks/{taskId}/applications", 10))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_APPLIED"));
    }

    /** 雇主查看报名者：200 + 分页视图，含公开昵称与报名时间。 */
    @Test
    void employerListShouldReturnApplications() throws Exception {
        loginAs(EMPLOYER);
        ApplicationListItemView item = new ApplicationListItemView(5L, 2001L, "小王",
                ApplicationStatus.APPLY_PENDING, LocalDateTime.of(2026, 12, 1, 9, 0));
        when(applicationService.list(eq(EMPLOYER), eq(10L), any(ApplicationPageQuery.class)))
                .thenReturn(PageView.of(List.of(item), 2, 5, 6));

        mockMvc.perform(get("/api/tasks/{taskId}/applications", 10)
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.total").value("6"))
                .andExpect(jsonPath("$.data.items[0].workerId").value("2001"))
                .andExpect(jsonPath("$.data.items[0].workerNickname").value("小王"))
                .andExpect(jsonPath("$.data.items[0].status").value("APPLY_PENDING"));

        ArgumentCaptor<ApplicationPageQuery> captor = ArgumentCaptor.forClass(ApplicationPageQuery.class);
        verify(applicationService).list(eq(EMPLOYER), eq(10L), captor.capture());
        assertThat(captor.getValue().page()).isEqualTo(2);
        assertThat(captor.getValue().size()).isEqualTo(5);
    }

    /** 分页参数非法：400 + INVALID_PARAMETER，且不进入 Service。 */
    @Test
    void listShouldRejectInvalidSizeBeforeService() throws Exception {
        loginAs(EMPLOYER);

        mockMvc.perform(get("/api/tasks/{taskId}/applications", 10).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.data.fields.size").exists());

        verifyNoInteractions(applicationService);
    }

    /** 劳动者查看报名者：403 + FORBIDDEN（本任务报名者列表只对雇主开放）。 */
    @Test
    void listShouldReturnForbiddenForWorker() throws Exception {
        loginAs(WORKER);
        doThrow(new ApiException(ErrorCode.FORBIDDEN, "仅雇主可查看报名者"))
                .when(applicationService).list(eq(WORKER), eq(10L), any(ApplicationPageQuery.class));

        mockMvc.perform(get("/api/tasks/{taskId}/applications", 10))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** 录用成功：200 + data.contractId 字符串化。 */
    @Test
    void employerHireShouldReturnStringContractId() throws Exception {
        loginAs(EMPLOYER);
        when(hireService.hire(EMPLOYER, 10L, 31L)).thenReturn(1234567890123456789L);

        mockMvc.perform(post("/api/tasks/{taskId}/applications/{applicationId}/hire", 10, 31))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.contractId").value("1234567890123456789"));
    }

    /** 名额被抢光：409 + SLOT_TAKEN（FR-SIGN.HIRE.03）。 */
    @Test
    void hireShouldReturnConflictWhenSlotTaken() throws Exception {
        loginAs(EMPLOYER);
        doThrow(new ApiException(ErrorCode.SLOT_TAKEN, "名额已被占用，请刷新后重试"))
                .when(hireService).hire(EMPLOYER, 10L, 31L);

        mockMvc.perform(post("/api/tasks/{taskId}/applications/{applicationId}/hire", 10, 31))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_TAKEN"));
    }

    private static void loginAs(LoginUser principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
