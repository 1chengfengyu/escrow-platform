package com.escrow.platform.fund.api;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.fund.application.FundAccountService;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资金账户只读入口（FR-WALLET.QUERY.01）。
 *
 * <p>Controller 只做三件事：取会话主体、转交 application 层、包 {@link ApiResponse}。
 * 充值入口单独放在 {@link FundRechargeController}，便于按环境整体摘除。</p>
 *
 * @since V2
 */
@RestController
@RequestMapping("/api/fund")
@RequiredArgsConstructor
public class FundController {

    private static final String UNAUTHENTICATED_MESSAGE = "未认证或登录已失效";

    private final FundAccountService fundAccountService;

    /** 本人可用 / 冻结余额。 */
    @GetMapping("/accounts/me")
    public ApiResponse<FundBalanceView> myAccounts(@AuthenticationPrincipal LoginUser current) {
        if (current == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_MESSAGE);
        }
        return ApiResponse.ok(fundAccountService.balances(current.userId()));
    }
}
