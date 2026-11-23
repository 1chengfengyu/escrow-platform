package com.escrow.platform.identity.api;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.security.RestAccessDeniedHandler;
import com.escrow.platform.common.security.RestAuthenticationEntryPoint;
import com.escrow.platform.common.security.SecurityConfig;
import com.escrow.platform.identity.application.IdentityProfileService;
import com.escrow.platform.identity.application.IdentitySwitchService;
import com.escrow.platform.identity.application.LoginService;
import com.escrow.platform.identity.application.RegisterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册接口契约测试（v1-c3 第 1.5 节，依据 docs/decisions.md #29~#31）。
 *
 * <p>{@code @WebMvcTest} 只装配 MVC 切片：不连 MySQL / Redis、不跑 Flyway。
 * 切片用 {@code TypeExcludeFilter} 过滤掉 {@code MyBatisMapperScanConfig}，
 * 因此不注册 Mapper、也不需要为 Mapper 准备替身（docs/decisions.md #44【V2 修订】）。</p>
 *
 * <p>重点验证两类 400 的边界：业务字段错误走 {@code INVALID_PARAMETER} + {@code data.fields}，
 * 请求体无法解析走 {@code VALIDATION_FAILED}，两者不能被兜成同一个码。</p>
 *
 * @since V1
 */
@WebMvcTest(controllers = IdentityController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        JacksonConfig.class})
class IdentityControllerTest {

    private static final String REGISTER_BODY = """
            {"phone":"13800138000","password":"abc12345","nickname":"小明","role":"WORKER"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegisterService registerService;

    /** c4 起 Controller 多了登录 / 切身份 / 当前登录者三个入口，本类只测注册，故替身即可。 */
    @MockitoBean
    private LoginService loginService;

    @MockitoBean
    private IdentitySwitchService identitySwitchService;

    @MockitoBean
    private IdentityProfileService identityProfileService;

    /** 成功：200 + code=SUCCESS + data.userId 非空。 */
    @Test
    void registerShouldReturnUserId() throws Exception {
        when(registerService.register(any(RegisterRequest.class), anyString())).thenReturn(1001L);

        mockMvc.perform(post("/api/identity/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.userId").value("1001"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    /** 字段不合法：400 + INVALID_PARAMETER + data.fields 指明字段（FR-ACCT.REG.01）。 */
    @Test
    void registerShouldReturnInvalidParameterWithFields() throws Exception {
        when(registerService.register(any(RegisterRequest.class), anyString()))
                .thenThrow(new ApiException(ErrorCode.INVALID_PARAMETER, "请求参数不合法",
                        Map.of("fields", Map.of("phone", "手机号格式不合法"))));

        mockMvc.perform(post("/api/identity/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.data.fields.phone").value("手机号格式不合法"));
    }

    /** 手机号或邮箱已存在：409 + ACCOUNT_EXISTS（FR-ACCT.REG.02）。 */
    @Test
    void registerShouldReturnAccountExists() throws Exception {
        when(registerService.register(any(RegisterRequest.class), anyString()))
                .thenThrow(new ApiException(ErrorCode.ACCOUNT_EXISTS, "手机号或邮箱已被注册"));

        mockMvc.perform(post("/api/identity/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_EXISTS"))
                .andExpect(jsonPath("$.data").value((Object) null));
    }

    /** 请求体不是合法 JSON：400 + VALIDATION_FAILED，证明与 INVALID_PARAMETER 没混。 */
    @Test
    void malformedJsonShouldReturnValidationFailed() throws Exception {
        mockMvc.perform(post("/api/identity/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(registerService, never()).register(any(), anyString());
    }
}
