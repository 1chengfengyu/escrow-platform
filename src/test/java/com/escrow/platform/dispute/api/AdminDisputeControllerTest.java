package com.escrow.platform.dispute.api;

import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.dispute.application.DisputeResolutionService;
import com.escrow.platform.dispute.application.DisputeService;
import com.escrow.platform.dispute.application.DisputeTxService;
import com.escrow.platform.dispute.domain.DisputeInitiatorType;
import com.escrow.platform.dispute.domain.DisputeResolution;
import com.escrow.platform.dispute.domain.DisputeStatus;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 争议管理端接口契约测试（V6）：列表 / 超期 / 详情 / 裁决。
 *
 * @since V6
 */
@WebMvcTest(controllers = AdminDisputeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class AdminDisputeControllerTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "13900139000", UserRole.ADMIN, IdentityType.ADMIN);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DisputeService disputeService;
    @MockitoBean
    private DisputeResolutionService disputeResolutionService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(LoginUser user) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    private DisputeSummaryView summary() {
        return new DisputeSummaryView(60L, 9L, DisputeInitiatorType.EMPLOYER, "交付不合格",
                DisputeStatus.DISPUTE_PENDING, 0, LocalDateTime.now().minusDays(8), 8L);
    }

    /** 列表按状态过滤。 */
    @Test
    void listShouldReturnSummaries() throws Exception {
        loginAs(ADMIN);
        when(disputeService.listForAdmin(any(), eq(DisputeStatus.DISPUTE_PENDING)))
                .thenReturn(List.of(summary()));

        mockMvc.perform(get("/api/admin/disputes").param("status", "DISPUTE_PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].id").value(60))
                .andExpect(jsonPath("$.data[0].initiatorType").value("EMPLOYER"))
                .andExpect(jsonPath("$.data[0].pendingDays").value(8));
    }

    /** 超期列表默认 7 天。 */
    @Test
    void overdueShouldUseDefaultDays() throws Exception {
        loginAs(ADMIN);
        when(disputeService.overdue(any(), eq(7))).thenReturn(List.of(summary()));

        mockMvc.perform(get("/api/admin/disputes/overdue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].pendingDays").value(8));
    }

    /** 详情返回裁决依据与证据。 */
    @Test
    void detailShouldReturnView() throws Exception {
        loginAs(ADMIN);
        when(disputeService.detail(any(), eq(60L))).thenReturn(new DisputeView(60L, 9L,
                DisputeInitiatorType.WORKER, "交付不公", DisputeStatus.DISPUTE_RESOLVED,
                DisputeResolution.RELEASE, "证据充分", 9001L, LocalDateTime.now(), 1,
                LocalDateTime.now(), List.of()));

        mockMvc.perform(get("/api/admin/disputes/60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolution").value("RELEASE"))
                .andExpect(jsonPath("$.data.resolutionBasis").value("证据充分"));
    }

    /** 裁决放款返回打款单 ID。 */
    @Test
    void resolveShouldReturnPayoutId() throws Exception {
        loginAs(ADMIN);
        when(disputeResolutionService.resolve(any(), eq(60L), eq(DisputeResolution.RELEASE),
                eq("证据充分"))).thenReturn(new DisputeTxService.ResolutionOutcome(60L, 700L));

        mockMvc.perform(post("/api/admin/disputes/60/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"RELEASE\",\"basis\":\"证据充分\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disputeId").value(60))
                .andExpect(jsonPath("$.data.payoutId").value(700));
    }

    /** 裁决依据为空：400。 */
    @Test
    void resolveWithBlankBasisShouldFailValidation() throws Exception {
        loginAs(ADMIN);

        mockMvc.perform(post("/api/admin/disputes/60/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"REFUND\",\"basis\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
