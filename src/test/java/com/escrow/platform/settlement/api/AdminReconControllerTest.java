package com.escrow.platform.settlement.api;

import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.settlement.application.ReconHandlingService;
import com.escrow.platform.settlement.application.ReconReport;
import com.escrow.platform.settlement.application.ReconRun;
import com.escrow.platform.settlement.domain.DifferenceStatus;
import com.escrow.platform.settlement.domain.DifferenceType;
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

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对账管理端接口契约测试（V6）。
 *
 * @since V6
 */
@WebMvcTest(controllers = AdminReconController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class AdminReconControllerTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "13900139000", UserRole.ADMIN, IdentityType.ADMIN);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReconHandlingService reconHandlingService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(LoginUser user) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    private ReconDifferenceView view() {
        return new ReconDifferenceView(60L, LocalDate.now(), DifferenceType.LOST_RESPONSE,
                "PO60", "PO60", null, 900L, DifferenceStatus.SETTLEMENT_DIFF_PENDING,
                null, null, null);
    }

    /** 列表：返回分页结构，total 序列化为字符串。 */
    @Test
    void listShouldReturnPageView() throws Exception {
        loginAs(ADMIN);
        when(reconHandlingService.list(any(), eq("SETTLEMENT_DIFF_PENDING"), any(), anyInt(), anyInt()))
                .thenReturn(PageView.of(List.of(view()), 1, 20, 1));

        mockMvc.perform(get("/api/admin/recon/differences")
                        .param("status", "SETTLEMENT_DIFF_PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].id").value(60))
                .andExpect(jsonPath("$.data.items[0].diffType").value("LOST_RESPONSE"))
                .andExpect(jsonPath("$.data.total").value("1"));
    }

    /** 非管理员：403 + FORBIDDEN。 */
    @Test
    void nonAdminShouldGetForbidden() throws Exception {
        loginAs(new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER));
        when(reconHandlingService.list(any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new ApiException(ErrorCode.FORBIDDEN, "仅管理员可处置对账差异"));

        mockMvc.perform(get("/api/admin/recon/differences"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** 处置：返回处置后的差异单。 */
    @Test
    void handleShouldReturnUpdatedDifference() throws Exception {
        loginAs(ADMIN);
        when(reconHandlingService.handle(any(), eq(60L), any())).thenReturn(
                new ReconDifferenceView(60L, LocalDate.now(), DifferenceType.LOST_RESPONSE,
                        "PO60", "PO60", null, 900L, DifferenceStatus.SETTLEMENT_DIFF_HANDLED,
                        com.escrow.platform.settlement.domain.DifferenceResolution.RECON_BOOK,
                        9001L, null));

        mockMvc.perform(post("/api/admin/recon/differences/60/handle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"RECON_BOOK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SETTLEMENT_DIFF_HANDLED"))
                .andExpect(jsonPath("$.data.resolution").value("RECON_BOOK"));
    }

    /** 缺 resolution：400。 */
    @Test
    void handleWithoutResolutionShouldFailValidation() throws Exception {
        loginAs(ADMIN);

        mockMvc.perform(post("/api/admin/recon/differences/60/handle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"无影响\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 日报：返回汇总字段。 */
    @Test
    void reportShouldReturnSummary() throws Exception {
        loginAs(ADMIN);
        when(reconHandlingService.report(any(), any())).thenReturn(
                new ReconReport(LocalDate.now(), 1L, "RECON_DONE", 1_000L, 500L, 1_500L,
                        2, 300L, 1, true));

        mockMvc.perform(get("/api/admin/recon/report").param("date", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECON_DONE"))
                .andExpect(jsonPath("$.data.pendingCount").value(1))
                .andExpect(jsonPath("$.data.identityOk").value(true));
    }

    /** 手动对账：返回批次结果。 */
    @Test
    void runShouldReturnBatchResult() throws Exception {
        loginAs(ADMIN);
        when(reconHandlingService.run(any(), any())).thenReturn(
                new ReconRun(1L, LocalDate.now(), 3, 3, 0, 0L, true));

        mockMvc.perform(post("/api/admin/recon/run").param("date", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.diffCount").value(0));
    }
}
