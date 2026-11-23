package com.escrow.platform.admin.api;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.identity.api.QualificationListItem;
import com.escrow.platform.identity.api.QualificationReviewRequest;
import com.escrow.platform.identity.application.QualificationReviewService;
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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端资质审核接口契约测试（v1-c5 §8 第 6 条，FR-ADMIN.QUAL.01）。
 *
 * <p>{@code addFilters = false}：ADMIN 鉴权在 application 层，这里验证「业务异常 → 状态码 / 响应体」，
 * 过滤器级 401 由 c1 的契约测试覆盖。</p>
 *
 * @since V1
 */
@WebMvcTest(controllers = AdminQualificationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class AdminQualificationControllerTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "13900139000", UserRole.ADMIN, IdentityType.ADMIN);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QualificationReviewService qualificationReviewService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 非 ADMIN 访问列表：403 + FORBIDDEN。 */
    @Test
    void nonAdminListShouldReturnForbidden() throws Exception {
        loginAs(new LoginUser(1002L, "13800138000", UserRole.WORKER, IdentityType.WORKER));
        when(qualificationReviewService.list(any(), any()))
                .thenThrow(new ApiException(ErrorCode.FORBIDDEN, "仅管理员可审核资质"));

        mockMvc.perform(get("/api/admin/qualifications"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** ADMIN 查看待审列表：200 + 列表字段（含昵称）。 */
    @Test
    void adminListShouldReturnItems() throws Exception {
        loginAs(ADMIN);
        when(qualificationReviewService.list(any(), any())).thenReturn(List.of(
                new QualificationListItem(1001L, "张三", "INDIVIDUAL", "张三", "1234",
                        LocalDateTime.of(2026, 11, 16, 10, 0))));

        mockMvc.perform(get("/api/admin/qualifications").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].userId").value("1001"))
                .andExpect(jsonPath("$.data[0].nickname").value("张三"))
                .andExpect(jsonPath("$.data[0].subjectType").value("INDIVIDUAL"))
                .andExpect(jsonPath("$.data[0].idLast4").value("1234"));
    }

    /** 审核通过：200 + SUCCESS。 */
    @Test
    void adminReviewShouldReturnSuccess() throws Exception {
        loginAs(ADMIN);

        mockMvc.perform(post("/api/admin/qualifications/1001/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(qualificationReviewService).review(any(), any(), any(QualificationReviewRequest.class));
    }

    /** 非法 decision：400 + INVALID_PARAMETER + data.fields。 */
    @Test
    void invalidDecisionShouldReturnFieldsInData() throws Exception {
        loginAs(ADMIN);
        doThrow(new ApiException(ErrorCode.INVALID_PARAMETER, "请求参数不合法",
                Map.of("fields", Map.of("decision", "decision 只允许 APPROVED 或 REJECTED"))))
                .when(qualificationReviewService).review(any(), any(), any());

        mockMvc.perform(post("/api/admin/qualifications/1001/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"PENDING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.data.fields.decision").value("decision 只允许 APPROVED 或 REJECTED"));
    }

    /** 手工装配会话主体，等价于过滤器从会话里恢复出的 Authentication。 */
    private static void loginAs(LoginUser principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
