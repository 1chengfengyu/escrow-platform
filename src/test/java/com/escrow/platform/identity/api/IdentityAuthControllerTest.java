package com.escrow.platform.identity.api;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.identity.application.IdentityProfileService;
import com.escrow.platform.identity.application.IdentitySwitchService;
import com.escrow.platform.identity.application.LoginService;
import com.escrow.platform.identity.application.RegisterService;
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

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录 / 切身份 / 当前登录者 接口契约测试（v1-c4 §4 第 4 条）。
 *
 * <p>{@code addFilters = false}：卡要求「不连真 Redis」，而开了过滤器链就必须有会话仓库。
 * 这里只验证「Controller → 业务异常 → 统一响应体 / Retry-After 头」这条链路，
 * 401/403 的过滤器级契约由 c1 的 {@code SecurityContractTest} 继续覆盖，不重复。</p>
 *
 * <p>主体通过 {@link SecurityContextHolder} 手工注入，等价于过滤器从会话里恢复出的状态；
 * 不用 {@code spring-security-test}（本卡只允许新增一条依赖）。</p>
 *
 * @since V1
 */
@WebMvcTest(controllers = IdentityController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class IdentityAuthControllerTest {

    private static final String LOGIN_BODY = """
            {"account":"13800138000","password":"abc12345"}
            """;

    private static final LoginUser LOGIN_USER =
            new LoginUser(1001L, "13800138000", UserRole.WORKER, IdentityType.WORKER);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoginService loginService;

    @MockitoBean
    private RegisterService registerService;

    @MockitoBean
    private IdentitySwitchService identitySwitchService;

    @MockitoBean
    private IdentityProfileService identityProfileService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 登录成功：200 + 当前身份 + 已开通身份列表。 */
    @Test
    void loginShouldReturnCurrentUser() throws Exception {
        when(loginService.login(any(LoginRequest.class), anyString()))
                .thenReturn(new LoginResponse(1001L, "小明", "WORKER", "WORKER", List.of("WORKER")));

        mockMvc.perform(post("/api/identity/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.userId").value("1001"))
                .andExpect(jsonPath("$.data.currentIdentity").value("WORKER"))
                .andExpect(jsonPath("$.data.identities[0]").value("WORKER"));
    }

    /** 口令错 / 账号不存在：401 + UNAUTHENTICATED（LOGIN.03，两者对外不可区分）。 */
    @Test
    void loginShouldReturnUnauthenticated() throws Exception {
        when(loginService.login(any(LoginRequest.class), anyString()))
                .thenThrow(new ApiException(ErrorCode.UNAUTHENTICATED, "账号或口令不正确"));

        mockMvc.perform(post("/api/identity/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /** 连续 5 次失败：423 + ACCOUNT_LOCKED（LOGIN.02）。 */
    @Test
    void loginShouldReturnAccountLocked() throws Exception {
        when(loginService.login(any(LoginRequest.class), anyString()))
                .thenThrow(new ApiException(ErrorCode.ACCOUNT_LOCKED, "账号已锁定，请稍后重试"));

        mockMvc.perform(post("/api/identity/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    /** 限流：429 + RATE_LIMITED + Retry-After 响应头（NFR-SEC.06、docs/decisions.md #31）。 */
    @Test
    void loginShouldReturnRateLimitedWithRetryAfterHeader() throws Exception {
        when(loginService.login(any(LoginRequest.class), anyString()))
                .thenThrow(new ApiException(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试",
                        Map.of("retryAfter", 42L)));

        mockMvc.perform(post("/api/identity/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.data.retryAfter").value("42"));
    }

    /** 已登录访问 /me：200 + 会话里的当前身份（ROLE.03 的观测点）。 */
    @Test
    void meShouldReturnSessionIdentity() throws Exception {
        loginAs(LOGIN_USER, IdentityType.EMPLOYER);
        when(identityProfileService.me(any()))
                .thenReturn(new IdentityMeResponse(1001L, "小明", "WORKER", "EMPLOYER",
                        List.of("EMPLOYER", "WORKER")));

        mockMvc.perform(get("/api/identity/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.currentIdentity").value("EMPLOYER"))
                .andExpect(jsonPath("$.data.identities[1]").value("WORKER"));
    }

    /** 切到未开通身份：403 + IDENTITY_NOT_AVAILABLE（ROLE.02）。 */
    @Test
    void switchShouldReturnIdentityNotAvailable() throws Exception {
        loginAs(LOGIN_USER, IdentityType.WORKER);
        when(identitySwitchService.switchIdentity(any(), any()))
                .thenThrow(new ApiException(ErrorCode.IDENTITY_NOT_AVAILABLE, "当前身份不可用"));

        mockMvc.perform(post("/api/identity/switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityType\":\"EMPLOYER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_NOT_AVAILABLE"));
    }

    /** 手工装配当前会话主体，等价于过滤器从 Redis 会话里恢复出的 Authentication。 */
    private static void loginAs(LoginUser user, IdentityType currentIdentity) {
        LoginUser principal = new LoginUser(user.userId(), user.account(), user.defaultRole(), currentIdentity);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
