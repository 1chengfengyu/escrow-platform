package com.escrow.platform.common.security;

import com.escrow.platform.common.web.PingController;
import com.escrow.platform.common.web.TraceIdFilter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全契约测试（v1-c1 第 1.5 节，依据 docs/decisions.md #29 / #31）。
 *
 * <p>{@code @WebMvcTest} 只装配 MVC 切片：不连 MySQL / Redis，也不跑 Flyway，
 * 保证「数据库未启动时 {@code mvn test} 必须是绿的」这条硬约束。</p>
 *
 * <p>过滤器顺序手工指定为「链路号过滤器 → Security 过滤器链」，
 * 与运行期顺序一致；顺序反了 {@code traceId} 会是空串。</p>
 *
 * @since V1
 */
@WebMvcTest(controllers = {WhoAmIController.class, PingController.class})
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        TraceIdFilter.class})
class SecurityContractTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new TraceIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    /** 未登录访问受保护接口：401 + 四字段契约 + traceId 非空（#31 未认证一行）。 */
    @Test
    void whoamiShouldReturnUnauthenticatedWhenAnonymous() throws Exception {
        mockMvc.perform(get("/api/sec/whoami"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("未认证或登录已失效"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    /** V0 回归：放行清单里的 /api/ping 仍然 200，引 Security 不许把它拦成 401。 */
    @Test
    void pingShouldStillReturnSuccessWithSecurityEnabled() throws Exception {
        mockMvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.pong").value(true))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    /**
     * 白名单必须覆盖整棵 {@code /api/ping/**} 子树：未认证访问子路径也要走到业务校验（400），
     * 而不是被 Security 拦成 401——否则 V0 的 400 演示在 V1 合并后直接断掉（docs/decisions.md #32）。
     */
    @Test
    void pingEchoShouldReturnValidationFailedInsteadOfUnauthenticatedWhenAnonymous() throws Exception {
        mockMvc.perform(get("/api/ping/echo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
