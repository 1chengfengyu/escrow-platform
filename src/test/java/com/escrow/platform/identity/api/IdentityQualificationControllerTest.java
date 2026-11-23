package com.escrow.platform.identity.api;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.RestAccessDeniedHandler;
import com.escrow.platform.common.security.RestAuthenticationEntryPoint;
import com.escrow.platform.common.security.SecurityConfig;
import com.escrow.platform.common.web.TraceIdFilter;
import com.escrow.platform.identity.application.QualificationService;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

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
 * 雇主资质自助接口契约测试（v1-c5 §8 第 5 条）。
 *
 * <p>两个 MockMvc：</p>
 * <ul>
 *   <li>{@code mockMvc}（{@code addFilters=false}）验证 Controller → 业务异常 → 统一响应体；</li>
 *   <li>{@code filteredMockMvc}（带 Security 过滤器链）只用于「无 Cookie → 401」这一条，
 *       与 c1 的 {@code SecurityContractTest} 同一手法。会话主体在过滤器链下会被清空，
 *       所以已登录用例一律走不加过滤器的那个。</li>
 * </ul>
 *
 * @since V1
 */
@WebMvcTest(controllers = QualificationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        TraceIdFilter.class})
class IdentityQualificationControllerTest {

    private static final String SUBMIT_BODY = """
            {"subjectType":"INDIVIDUAL","subjectName":"张三","idLast4":"1234"}
            """;

    private static final LoginUser EMPLOYER =
            new LoginUser(1001L, "13800138000", UserRole.EMPLOYER, IdentityType.EMPLOYER);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private QualificationService qualificationService;

    private MockMvc filteredMockMvc;

    @BeforeEach
    void setUp() {
        filteredMockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new TraceIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 雇主提交：200 + SUCCESS。 */
    @Test
    void employerSubmitShouldReturnSuccess() throws Exception {
        loginAs(EMPLOYER);

        mockMvc.perform(post("/api/identity/qualification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUBMIT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(qualificationService).submit(any(), any(QualificationSubmitRequest.class));
    }

    /** 非雇主身份提交：403 + FORBIDDEN（FR-ACCT.QUAL.01 的越权边界）。 */
    @Test
    void workerSubmitShouldReturnForbidden() throws Exception {
        loginAs(new LoginUser(1002L, "13900139000", UserRole.WORKER, IdentityType.WORKER));
        doThrow(new ApiException(ErrorCode.FORBIDDEN, "仅雇主身份可提交资质"))
                .when(qualificationService).submit(any(), any());

        mockMvc.perform(post("/api/identity/qualification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUBMIT_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** 字段不合法：400 + INVALID_PARAMETER + data.fields 指明字段。 */
    @Test
    void invalidSubmitShouldReturnFieldsInData() throws Exception {
        loginAs(EMPLOYER);
        doThrow(new ApiException(ErrorCode.INVALID_PARAMETER, "请求参数不合法",
                Map.of("fields", Map.of("idLast4", "证件号后 4 位必须是 4 位数字"))))
                .when(qualificationService).submit(any(), any());

        mockMvc.perform(post("/api/identity/qualification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"INDIVIDUAL\",\"subjectName\":\"张三\",\"idLast4\":\"12a4\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.data.fields.idLast4").value("证件号后 4 位必须是 4 位数字"));
    }

    /** 查询：200 + 状态 / 字段 / publishEligible 原样透出。 */
    @Test
    void getShouldReturnQualificationView() throws Exception {
        loginAs(EMPLOYER);
        when(qualificationService.get(any())).thenReturn(new QualificationView(
                "PENDING", "INDIVIDUAL", "张三", "1234", null, null, false));

        mockMvc.perform(get("/api/identity/qualification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.subjectType").value("INDIVIDUAL"))
                .andExpect(jsonPath("$.data.idLast4").value("1234"))
                .andExpect(jsonPath("$.data.publishEligible").value(false));
    }

    /** 查询已审记录：reviewedAt / reviewReason 正常序列化。 */
    @Test
    void getReviewedRecordShouldIncludeReviewFields() throws Exception {
        loginAs(EMPLOYER);
        when(qualificationService.get(any())).thenReturn(new QualificationView(
                "REJECTED", "INDIVIDUAL", "张三", "1234", "证件不清晰",
                LocalDateTime.of(2026, 11, 16, 11, 0), true));

        mockMvc.perform(get("/api/identity/qualification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewReason").value("证件不清晰"))
                .andExpect(jsonPath("$.data.reviewedAt").isNotEmpty());
    }

    /** 无 Cookie：401 + UNAUTHENTICATED（由过滤器链给出，不经过 Controller）。 */
    @Test
    void anonymousGetShouldReturnUnauthenticated() throws Exception {
        filteredMockMvc.perform(get("/api/identity/qualification"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /** 手工装配会话主体，等价于过滤器从 Redis 会话里恢复出的 Authentication。 */
    private static void loginAs(LoginUser principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
