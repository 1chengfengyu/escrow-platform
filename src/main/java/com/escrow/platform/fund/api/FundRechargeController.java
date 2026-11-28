package com.escrow.platform.fund.api;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.fund.application.FundAccountService;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模拟充值入口（FR-WALLET.RECHARGE.01~.02，V2 演示用）。
 *
 * <p>{@code @Profile("!prod")}：这是没有真实支付通道的模拟接口，
 * 生产环境不得暴露（用户裁决 ③ / 6A）。</p>
 *
 * <p>必须写流水：只加 {@code balance} 不写 {@code fund_journal_entry}，
 * V6 对账的「发行总额 = Σ 用户余额」会直接不平。</p>
 *
 * @since V2
 */
@RestController
@RequestMapping("/api/fund")
@Profile("!prod")
@RequiredArgsConstructor
public class FundRechargeController {

    private static final String UNAUTHENTICATED_MESSAGE = "未认证或登录已失效";
    private static final String AMOUNT_REQUIRED_MESSAGE = "充值金额不能为空";
    private static final String REQUEST_ID_INVALID_MESSAGE = "充值请求标识必须在 1~32 字符之间";

    /** 与 {@code fund_journal_batch.biz_no VARCHAR(32)} 对齐。 */
    private static final int MAX_REQUEST_ID_LENGTH = 32;

    private final FundAccountService fundAccountService;

    /** 模拟充值：返回充值后的余额视图。同一 {@code requestId} 重试不会重复入账。 */
    @PostMapping("/recharge")
    public ApiResponse<FundBalanceView> recharge(@RequestBody RechargeRequest request,
                                                 @AuthenticationPrincipal LoginUser current) {
        if (current == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_MESSAGE);
        }
        if (request == null || request.amount() == null) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, AMOUNT_REQUIRED_MESSAGE,
                    Map.of("fields", Map.of("amount", AMOUNT_REQUIRED_MESSAGE)));
        }
        String requestId = request.requestId();
        if (requestId == null || requestId.isBlank() || requestId.length() > MAX_REQUEST_ID_LENGTH) {
            // 在这里拦而不是漏到 JournalService.validate：那里抛 IllegalArgumentException，
            // 会被兜成 500。参数错不该表现成服务器错。
            throw new ApiException(ErrorCode.INVALID_PARAMETER, REQUEST_ID_INVALID_MESSAGE,
                    Map.of("fields", Map.of("requestId", REQUEST_ID_INVALID_MESSAGE)));
        }
        return ApiResponse.ok(fundAccountService.recharge(current.userId(), requestId, request.amount()));
    }
}
