package com.escrow.platform.fund.api;

import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.RestAccessDeniedHandler;
import com.escrow.platform.common.security.RestAuthenticationEntryPoint;
import com.escrow.platform.common.security.SecurityConfig;
import com.escrow.platform.common.web.TraceIdFilter;
import com.escrow.platform.fund.application.FundAccountService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 资金接口契约测试（v2-c5）：余额查询与模拟充值。
 *
 * <p>充值端点是 {@code @Profile("!prod")}，切片测试的默认 profile 不是 prod，因此会被加载。</p>
 *
 * @since V2
 */
@WebMvcTest(controllers = {FundController.class, FundRechargeController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        TraceIdFilter.class, JacksonConfig.class})
class FundControllerTest {

    private static final LoginUser EMPLOYER =
            new LoginUser(1001L, "13800138000", UserRole.EMPLOYER, IdentityType.EMPLOYER);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FundAccountService fundAccountService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 本人余额：200 + 两个科目金额（字符串化）。 */
    @Test
    void myAccountsShouldReturnBalances() throws Exception {
        loginAs(EMPLOYER);
        when(fundAccountService.balances(1001L)).thenReturn(new FundBalanceView(5000L, 3000L));

        mockMvc.perform(get("/api/fund/accounts/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value("5000"))
                .andExpect(jsonPath("$.data.frozen").value("3000"));
    }

    /** 未登录：401 UNAUTHENTICATED。 */
    @Test
    void myAccountsWithoutLoginShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/fund/accounts/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /** 模拟充值成功：200 + 充值后的余额。 */
    @Test
    void rechargeShouldReturnNewBalance() throws Exception {
        loginAs(EMPLOYER);
        when(fundAccountService.recharge(1001L, 5000L)).thenReturn(new FundBalanceView(5000L, 0L));

        mockMvc.perform(post("/api/fund/recharge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value("5000"));
    }

    /** 超过单笔限额：409 RECHARGE_LIMIT_EXCEEDED。 */
    @Test
    void rechargeAboveLimitShouldReturn409() throws Exception {
        loginAs(EMPLOYER);
        when(fundAccountService.recharge(anyLong(), anyLong()))
                .thenThrow(new ApiException(ErrorCode.RECHARGE_LIMIT_EXCEEDED, "充值金额超过单笔限额"));

        mockMvc.perform(post("/api/fund/recharge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":5000001}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECHARGE_LIMIT_EXCEEDED"));
    }

    /** 缺少金额：400 INVALID_PARAMETER，不进 Service。 */
    @Test
    void rechargeWithoutAmountShouldReturn400() throws Exception {
        loginAs(EMPLOYER);

        mockMvc.perform(post("/api/fund/recharge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":null}"))
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
