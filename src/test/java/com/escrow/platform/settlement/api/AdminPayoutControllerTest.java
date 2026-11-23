package com.escrow.platform.settlement.api;

import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.settlement.application.PayoutAdminService;
import com.escrow.platform.settlement.domain.PayoutStatus;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 打款处置台接口契约测试（V5）。
 *
 * @since V5
 */
@WebMvcTest(controllers = AdminPayoutController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class AdminPayoutControllerTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "13900139000", UserRole.ADMIN, IdentityType.ADMIN);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PayoutAdminService payoutAdminService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(LoginUser user) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    private PayoutAdminView view() {
        return new PayoutAdminView(70L, 80L, "PO70", 1, 970L, 960L,
                PayoutStatus.PAYOUT_MANUAL_REVIEW, null, LocalDateTime.now().minusHours(1), 60L);
    }

    /** 列表：返回分页结构。 */
    @Test
    void listShouldReturnPageView() throws Exception {
        loginAs(ADMIN);
        when(payoutAdminService.list(any(), eq("PAYOUT_MANUAL_REVIEW"), anyInt(), anyInt()))
                .thenReturn(PageView.of(List.of(view()), 1, 20, 1));

        mockMvc.perform(get("/api/admin/payouts")
                        .param("status", "PAYOUT_MANUAL_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].id").value(70))
                .andExpect(jsonPath("$.data.total").value("1"));
    }

    /** 非管理员：403 + FORBIDDEN。 */
    @Test
    void nonAdminShouldGetForbidden() throws Exception {
        loginAs(new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER));
        when(payoutAdminService.list(any(), any(), anyInt(), anyInt()))
                .thenThrow(new ApiException(ErrorCode.FORBIDDEN, "仅管理员可处置打款单"));

        mockMvc.perform(get("/api/admin/payouts"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** 账龄告警：返回数组。 */
    @Test
    void agingShouldReturnAlerts() throws Exception {
        loginAs(ADMIN);
        when(payoutAdminService.aging(any())).thenReturn(List.of(
                new PayoutAgingAlert(70L, 80L, "PO70", PayoutStatus.PAYOUT_MANUAL_REVIEW,
                        970L, LocalDateTime.now().minusHours(2), 120L)));

        mockMvc.perform(get("/api/admin/payouts/aging"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].payoutId").value(70))
                .andExpect(jsonPath("$.data[0].ageMinutes").value(120));
    }

    /** 处置：成功返回处置后的单据。 */
    @Test
    void decideShouldReturnUpdatedPayout() throws Exception {
        loginAs(ADMIN);
        when(payoutAdminService.decide(any(), eq(70L), any())).thenReturn(view());

        mockMvc.perform(post("/api/admin/payouts/70/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRM_ACTUAL\",\"actualAmount\":960,\"remark\":\"以对账单为准\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actualAmount").value(960));
    }

    /** 缺 decision：400。 */
    @Test
    void decideWithoutDecisionShouldFailValidation() throws Exception {
        loginAs(ADMIN);

        mockMvc.perform(post("/api/admin/payouts/70/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualAmount\":960}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
