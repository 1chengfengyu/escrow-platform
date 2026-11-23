package com.escrow.platform.dispute.api;

import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.dispute.application.ChatEvidenceService;
import com.escrow.platform.dispute.application.DisputeResolutionService;
import com.escrow.platform.dispute.application.DisputeService;
import com.escrow.platform.dispute.domain.DisputeInitiatorType;
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
 * 争议双方接口契约测试（V6）：申诉 / 详情 / 证据 / 撤回。
 *
 * @since V6
 */
@WebMvcTest(controllers = DisputeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class DisputeControllerTest {

    private static final LoginUser WORKER =
            new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DisputeResolutionService disputeResolutionService;
    @MockitoBean
    private DisputeService disputeService;
    @MockitoBean
    private ChatEvidenceService chatEvidenceService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(LoginUser user) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    /** 申诉返回争议单 ID；请求体可省略。 */
    @Test
    void appealWithoutBodyShouldReturnDisputeId() throws Exception {
        loginAs(WORKER);
        when(disputeResolutionService.appeal(any(), eq(7L), eq(null))).thenReturn(60L);

        mockMvc.perform(post("/api/contracts/7/disputes").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(60));
    }

    /** 提交证据返回证据视图。 */
    @Test
    void addEvidenceShouldReturnView() throws Exception {
        loginAs(WORKER);
        when(disputeService.addEvidence(any(), eq(60L), any())).thenReturn(
                new DisputeEvidenceView(70L, 60L, 1001L, DisputeInitiatorType.WORKER,
                        "已按约定交付", List.of("https://img.example.com/a.jpg"), LocalDateTime.now()));

        mockMvc.perform(post("/api/disputes/60/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"已按约定交付\",\"imageUrls\":[\"https://img.example.com/a.jpg\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(70))
                .andExpect(jsonPath("$.data.imageUrls[0]").value("https://img.example.com/a.jpg"));
    }

    /** 详情返回发起方与状态。 */
    @Test
    void detailShouldReturnView() throws Exception {
        loginAs(WORKER);
        when(disputeService.detail(any(), eq(60L))).thenReturn(new DisputeView(60L, 9L,
                DisputeInitiatorType.WORKER, "交付不公", DisputeStatus.DISPUTE_PENDING,
                null, null, null, null, 0, LocalDateTime.now(), List.of()));

        mockMvc.perform(get("/api/disputes/60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.initiatorType").value("WORKER"))
                .andExpect(jsonPath("$.data.status").value("DISPUTE_PENDING"));
    }

    /** 撤回成功返回空数据。 */
    @Test
    void withdrawShouldReturnOk() throws Exception {
        loginAs(WORKER);

        mockMvc.perform(post("/api/disputes/60/withdraw"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    /** 越权查看：403。 */
    @Test
    void detailOfForeignDisputeShouldBeForbidden() throws Exception {
        loginAs(WORKER);
        when(disputeService.detail(any(), eq(60L)))
                .thenThrow(new ApiException(ErrorCode.FORBIDDEN, "无权查看或操作该争议单"));

        mockMvc.perform(get("/api/disputes/60"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** FR-MSG.CHAT.02 / 补遗 §5.4：从会话取证返回复制的证据列表。 */
    @Test
    void addEvidenceFromChatShouldReturnCopiedEvidence() throws Exception {
        loginAs(WORKER);
        when(chatEvidenceService.copyFromChat(any(), eq(60L), any())).thenReturn(List.of(
                new DisputeEvidenceView(71L, 60L, 1001L, DisputeInitiatorType.WORKER,
                        "已按要求交付", List.of(), LocalDateTime.now())));

        mockMvc.perform(post("/api/disputes/60/evidence/from-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageIds\":[701,702]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].content").value("已按要求交付"))
                .andExpect(jsonPath("$.data[0].imageUrls").isEmpty());
    }

    /** 非当事人取证：403。 */
    @Test
    void addEvidenceFromChatShouldBeForbiddenForOutsider() throws Exception {
        loginAs(WORKER);
        when(chatEvidenceService.copyFromChat(any(), eq(60L), any()))
                .thenThrow(new ApiException(ErrorCode.FORBIDDEN, "无权为该争议提交证据"));

        mockMvc.perform(post("/api/disputes/60/evidence/from-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageIds\":[701]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
