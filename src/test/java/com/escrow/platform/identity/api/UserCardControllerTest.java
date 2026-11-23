package com.escrow.platform.identity.api;

import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.RestAccessDeniedHandler;
import com.escrow.platform.common.security.RestAuthenticationEntryPoint;
import com.escrow.platform.common.security.SecurityConfig;
import com.escrow.platform.common.web.TraceIdFilter;
import com.escrow.platform.identity.application.IdentityProfileService;
import com.escrow.platform.identity.application.IdentitySwitchService;
import com.escrow.platform.identity.application.LoginService;
import com.escrow.platform.identity.application.RegisterService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 他人名片接口契约测试（v1-c6 §8 第 4 条，NFR-SEC.03）。
 *
 * <p>两个 MockMvc 分工与 v1-c5 的资质接口测试一致：</p>
 * <ul>
 *   <li>{@code mockMvc}（{@code addFilters = false}）验证「序列化层打码 + 业务异常 → 统一响应体」；</li>
 *   <li>{@code filteredMockMvc} 只用于「无 Cookie → 401」，这条由过滤器链给出，不经过 Controller。</li>
 * </ul>
 *
 * <p>服务层替身一律返回<b>原值</b>：打码必须发生在 Jackson 序列化层，
 * 如果测试里预先给了打码值，就等于把打码逻辑搬进服务层，验不到本卡的实现。</p>
 *
 * @since V1
 */
@WebMvcTest(controllers = IdentityController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        TraceIdFilter.class, JacksonConfig.class})
class UserCardControllerTest {

    private static final String RAW_PHONE = "13800138000";
    private static final String RAW_EMAIL = "boss@example.com";

    private static final UserCardResponse CARD =
            new UserCardResponse(2002L, "甲方公司", RAW_PHONE, RAW_EMAIL, "EMPLOYER");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private IdentityProfileService identityProfileService;

    /** c3 / c4 的入口本类不测，替身只为满足 Controller 的构造依赖。 */
    @MockitoBean
    private RegisterService registerService;

    @MockitoBean
    private LoginService loginService;

    @MockitoBean
    private IdentitySwitchService identitySwitchService;

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

    /** 200：字段齐全，且 userId 已被全局 Long 字符串化。 */
    @Test
    void cardShouldReturnAllFields() throws Exception {
        loginAsWorker();
        when(identityProfileService.card(anyLong())).thenReturn(CARD);

        mockMvc.perform(get("/api/identity/users/2002/card"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.userId").value("2002"))
                .andExpect(jsonPath("$.data.nickname").value("甲方公司"))
                .andExpect(jsonPath("$.data.role").value("EMPLOYER"))
                .andExpect(jsonPath("$.data.phone").exists())
                .andExpect(jsonPath("$.data.email").exists());
    }

    /** 目标账号不存在：404 + RESOURCE_NOT_FOUND。 */
    @Test
    void missingUserShouldReturnNotFound() throws Exception {
        loginAsWorker();
        when(identityProfileService.card(anyLong()))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "目标用户不存在"));

        mockMvc.perform(get("/api/identity/users/9999/card"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /** 无 Cookie：401 + UNAUTHENTICATED（过滤器链给出，不经过 Controller）。 */
    @Test
    void anonymousShouldReturnUnauthenticated() throws Exception {
        filteredMockMvc.perform(get("/api/identity/users/2002/card"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /** 普通用户视角：手机号与邮箱都被打码（NFR-SEC.03）。 */
    @Test
    void nonAdminShouldSeeMaskedContact() throws Exception {
        loginAsWorker();
        when(identityProfileService.card(anyLong())).thenReturn(CARD);

        mockMvc.perform(get("/api/identity/users/2002/card"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("138****8000"))
                .andExpect(jsonPath("$.data.email").value("b***@example.com"));
    }

    /** 管理员视角：同一 URL 返回完整联系方式（docs/03 V1 演示第 5 条）。 */
    @Test
    void adminShouldSeeRawContact() throws Exception {
        loginAsAdmin();
        when(identityProfileService.card(anyLong())).thenReturn(CARD);

        mockMvc.perform(get("/api/identity/users/2002/card"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value(RAW_PHONE))
                .andExpect(jsonPath("$.data.email").value(RAW_EMAIL));
    }

    private static void loginAsWorker() {
        loginAs(new SimpleGrantedAuthority("ROLE_WORKER"));
    }

    private static void loginAsAdmin() {
        loginAs(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static void loginAs(SimpleGrantedAuthority authority) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("13800138000", null,
                List.of(authority)));
        SecurityContextHolder.setContext(context);
    }
}
