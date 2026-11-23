package com.escrow.platform.engagement.api;

import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.RestAccessDeniedHandler;
import com.escrow.platform.common.security.RestAuthenticationEntryPoint;
import com.escrow.platform.common.security.SecurityConfig;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.common.web.TraceIdFilter;
import com.escrow.platform.engagement.application.AcceptanceService;
import com.escrow.platform.engagement.application.AcceptanceTxService;
import com.escrow.platform.engagement.application.ContractQueryService;
import com.escrow.platform.engagement.application.ContractService;
import com.escrow.platform.engagement.domain.AcceptanceDecision;
import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.domain.SlotStatus;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 签约履约接口契约测试（FR-SIGN.START.01、FR-SIGN.CANCEL.01~.03、FR-DELIVER.SUBMIT.01~.03）。
 *
 * <p>切片只加载 {@link ContractController}，Service 用 {@code @MockitoBean} 隔离。</p>
 *
 * @since V3
 */
@WebMvcTest(controllers = ContractController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        TraceIdFilter.class, JacksonConfig.class})
class ContractControllerTest {

    private static final LoginUser WORKER =
            new LoginUser(2001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    private static final LoginUser EMPLOYER =
            new LoginUser(3001L, "employer@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContractService contractService;

    @MockitoBean
    private ContractQueryService contractQueryService;

    @MockitoBean
    private AcceptanceService acceptanceService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 开始履约成功：200 + contractId 字符串化 + 新状态。 */
    @Test
    void startShouldReturnStartedStatus() throws Exception {
        loginAs(WORKER);
        when(contractService.start(WORKER, 51L)).thenReturn(ContractStatus.CONTRACT_STARTED);

        mockMvc.perform(post("/api/contracts/{contractId}/start", 51))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.contractId").value("51"))
                .andExpect(jsonPath("$.data.status").value("CONTRACT_STARTED"));
    }

    /** 取消签约成功：200 + 已取消。 */
    @Test
    void cancelShouldReturnCancelledStatus() throws Exception {
        loginAs(WORKER);
        when(contractService.cancel(WORKER, 51L)).thenReturn(ContractStatus.CONTRACT_CANCELLED);

        mockMvc.perform(post("/api/contracts/{contractId}/cancel", 51))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.contractId").value("51"))
                .andExpect(jsonPath("$.data.status").value("CONTRACT_CANCELLED"));
    }

    /** 已开始履约再取消：409 + INVALID_STATE_TRANSITION（FR-SIGN.CANCEL.03）。 */
    @Test
    void cancelShouldReturnConflictWhenStarted() throws Exception {
        loginAs(WORKER);
        doThrow(new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "当前签约不可取消"))
                .when(contractService).cancel(WORKER, 51L);

        mockMvc.perform(post("/api/contracts/{contractId}/cancel", 51))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    /** 非本签约劳动者：403 + FORBIDDEN。 */
    @Test
    void startShouldReturnForbiddenForOtherWorker() throws Exception {
        loginAs(WORKER);
        doThrow(new ApiException(ErrorCode.FORBIDDEN, "无权操作他人签约"))
                .when(contractService).start(WORKER, 51L);

        mockMvc.perform(post("/api/contracts/{contractId}/start", 51))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** FR-DELIVER.SUBMIT.01~.03：提交交付物 200 + 已交付。 */
    @Test
    void deliverShouldReturnDeliveredStatus() throws Exception {
        loginAs(WORKER);
        when(contractService.deliver(eq(WORKER), eq(51L), any()))
                .thenReturn(ContractStatus.CONTRACT_DELIVERED);

        mockMvc.perform(post("/api/contracts/{contractId}/deliver", 51)
                        .contentType("application/json")
                        .content("{\"images\":[\"https://img.example.com/a.jpg\"],\"description\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.contractId").value("51"))
                .andExpect(jsonPath("$.data.status").value("CONTRACT_DELIVERED"));
    }

    /** 图片数量不合法：400 INVALID_PARAMETER，不进 Service。 */
    @Test
    void deliverShouldReturnBadRequestWhenImagesInvalid() throws Exception {
        loginAs(WORKER);

        mockMvc.perform(post("/api/contracts/{contractId}/deliver", 51)
                        .contentType("application/json")
                        .content("{\"images\":[],\"description\":\"ok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.data.fields.images").exists());
    }

    /** 未开始履约就交付：409 INVALID_STATE_TRANSITION。 */
    @Test
    void deliverShouldReturnConflictWhenNotStarted() throws Exception {
        loginAs(WORKER);
        doThrow(new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "当前签约不可交付"))
                .when(contractService).deliver(eq(WORKER), eq(51L), any());

        mockMvc.perform(post("/api/contracts/{contractId}/deliver", 51)
                        .contentType("application/json")
                        .content("{\"images\":[\"https://img.example.com/a.jpg\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    /** 我的签约：200 + 分页视图，只含当前劳动者数据（数据权限由 Service 保证）。 */
    @Test
    void mineShouldReturnPageOfOwnContracts() throws Exception {
        loginAs(WORKER);
        ContractListItemView item = new ContractListItemView(51L, 10L, 21L,
                ContractStatus.CONTRACT_DELIVERED,
                LocalDateTime.of(2026, 11, 18, 9, 0),
                LocalDateTime.of(2026, 11, 18, 10, 0),
                LocalDateTime.of(2026, 11, 18, 18, 0));
        when(contractQueryService.listMine(eq(WORKER), any()))
                .thenReturn(PageView.of(List.of(item), 1, 20, 1));

        mockMvc.perform(get("/api/contracts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].contractId").value("51"))
                .andExpect(jsonPath("$.data.items[0].status").value("CONTRACT_DELIVERED"))
                .andExpect(jsonPath("$.data.total").value("1"));
    }

    /** 我的签约分页参数非法：400 INVALID_PARAMETER，不进 Service。 */
    @Test
    void mineShouldReturnBadRequestOnInvalidPage() throws Exception {
        loginAs(WORKER);

        mockMvc.perform(get("/api/contracts").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    /** FR-ACCEPT.MANUAL.01：雇主验收通过 200 + 名额待结算。 */
    @Test
    void acceptanceShouldReturnPendingSettlementOnPass() throws Exception {
        loginAs(EMPLOYER);
        when(acceptanceService.accept(eq(EMPLOYER), eq(51L), eq(AcceptanceDecision.PASS), any()))
                .thenReturn(new AcceptanceTxService.AcceptanceOutcome(51L,
                        ContractStatus.CONTRACT_COMPLETED, SlotStatus.SLOT_PENDING_SETTLEMENT,
                        null, null, null));

        mockMvc.perform(post("/api/contracts/{contractId}/acceptance", 51)
                        .contentType("application/json")
                        .content("{\"decision\":\"PASS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.contractId").value("51"))
                .andExpect(jsonPath("$.data.contractStatus").value("CONTRACT_COMPLETED"))
                .andExpect(jsonPath("$.data.slotStatus").value("SLOT_PENDING_SETTLEMENT"));
    }

    /** FR-ACCEPT.GUARD.01：名额待裁决时验收返回 409 DISPUTE_ACTIVE。 */
    @Test
    void acceptanceShouldReturnConflictWhenDisputeActive() throws Exception {
        loginAs(EMPLOYER);
        doThrow(new ApiException(ErrorCode.DISPUTE_ACTIVE, "争议处理中，暂不可验收"))
                .when(acceptanceService).accept(eq(EMPLOYER), eq(51L), any(), any());

        mockMvc.perform(post("/api/contracts/{contractId}/acceptance", 51)
                        .contentType("application/json")
                        .content("{\"decision\":\"PASS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_ACTIVE"));
    }

    /** 验收结论非法：400 INVALID_PARAMETER，不进 Service。 */
    @Test
    void acceptanceShouldReturnBadRequestOnInvalidDecision() throws Exception {
        loginAs(EMPLOYER);

        mockMvc.perform(post("/api/contracts/{contractId}/acceptance", 51)
                        .contentType("application/json")
                        .content("{\"decision\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    private static void loginAs(LoginUser principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
