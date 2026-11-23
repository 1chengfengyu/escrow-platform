package com.escrow.platform.engagement.api;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.engagement.application.AcceptanceService;
import com.escrow.platform.engagement.application.AcceptanceTxService;
import com.escrow.platform.engagement.application.ContractQueryService;
import com.escrow.platform.engagement.application.ContractService;
import com.escrow.platform.engagement.domain.AcceptanceDecision;
import com.escrow.platform.engagement.domain.ContractPageQuery;
import com.escrow.platform.engagement.domain.DeliverableSubmission;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 签约履约入口（FR-SIGN.START.01、FR-SIGN.CANCEL.01~.03、FR-DELIVER.SUBMIT.01~.03）。
 *
 * <p>Controller 只做三件事：取会话主体、把请求体交给 domain 校验、转交 application 层。
 * 身份与归属判定在 Service；端点不在 {@code SecurityConfig.PUBLIC_PATHS} 里，未登录一律 401。</p>
 *
 * @since V3
 */
@RestController
@RequestMapping("/api/contracts")
@RequiredArgsConstructor
public class ContractController {

    private final ContractService contractService;
    private final ContractQueryService contractQueryService;
    private final AcceptanceService acceptanceService;

    /** 我的签约（数据权限第一层）：只返回当前劳动者的签约。 */
    @GetMapping
    public ApiResponse<PageView<ContractListItemView>> mine(
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size,
            @AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(contractQueryService.listMine(
                current, ContractPageQuery.from(page, size)));
    }

    /** 劳动者开始履约：200 + {@code data.contractId / data.status}。 */
    @PostMapping("/{contractId}/start")
    public ApiResponse<ContractActionView> start(@PathVariable Long contractId,
                                                 @AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(new ContractActionView(contractId,
                contractService.start(current, contractId)));
    }

    /** 劳动者提交交付物：200 + {@code data.contractId / data.status}。 */
    @PostMapping("/{contractId}/deliver")
    public ApiResponse<ContractActionView> deliver(@PathVariable Long contractId,
                                                   @RequestBody DeliverRequest request,
                                                   @AuthenticationPrincipal LoginUser current) {
        DeliverableSubmission submission =
                DeliverableSubmission.from(request.images(), request.description());
        return ApiResponse.ok(new ContractActionView(contractId,
                contractService.deliver(current, contractId, submission)));
    }

    /** 劳动者取消签约：200 + {@code data.contractId / data.status}。 */
    @PostMapping("/{contractId}/cancel")
    public ApiResponse<ContractActionView> cancel(@PathVariable Long contractId,
                                                  @AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(new ContractActionView(contractId,
                contractService.cancel(current, contractId)));
    }

    /**
     * 雇主验收（FR-ACCEPT.MANUAL.01）：通过触发结算与打款，拒收进入待裁决。
     *
     * <p>请求体结论非法（非 PASS / REJECT）按 400 {@code INVALID_PARAMETER} 返回；
     * 名额处于待裁决时返回 409 {@code DISPUTE_ACTIVE}（FR-ACCEPT.GUARD.01）。</p>
     */
    @PostMapping("/{contractId}/acceptance")
    public ApiResponse<AcceptanceView> acceptance(@PathVariable Long contractId,
                                                  @RequestBody AcceptanceRequest request,
                                                  @AuthenticationPrincipal LoginUser current) {
        AcceptanceDecision decision;
        try {
            decision = AcceptanceDecision.parse(request.decision());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, ex.getMessage());
        }
        AcceptanceTxService.AcceptanceOutcome outcome =
                acceptanceService.accept(current, contractId, decision, request.reason());
        return ApiResponse.ok(new AcceptanceView(outcome.contractId(), outcome.contractStatus(),
                outcome.slotStatus(), outcome.disputeId(), outcome.settlementId(), outcome.payoutId()));
    }
}
