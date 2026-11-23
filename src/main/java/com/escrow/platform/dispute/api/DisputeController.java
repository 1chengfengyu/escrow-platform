package com.escrow.platform.dispute.api;

import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.dispute.application.ChatEvidenceService;
import com.escrow.platform.dispute.application.DisputeResolutionService;
import com.escrow.platform.dispute.application.DisputeService;
import com.escrow.platform.identity.domain.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 争议双方入口（V6）：申诉、撤回、证据、详情。
 *
 * <p>Controller 只做转发；当事人校验在应用层（争议可见性按名额归属判定）。</p>
 *
 * @since V6
 */
@RestController
@RequiredArgsConstructor
public class DisputeController {

    private final DisputeResolutionService disputeResolutionService;
    private final DisputeService disputeService;
    private final ChatEvidenceService chatEvidenceService;

    /**
     * 劳动者申诉开争议（FR-DISPUTE.OPEN.01）。
     *
     * @param current    会话主体
     * @param contractId 签约 ID
     * @param request    申诉理由，可空
     * @return 争议单 ID
     */
    @PostMapping("/api/contracts/{contractId}/disputes")
    public ApiResponse<Long> appeal(@AuthenticationPrincipal LoginUser current,
                                    @PathVariable Long contractId,
                                    @Valid @RequestBody(required = false) DisputeAppealRequest request) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok(disputeResolutionService.appeal(current, contractId, reason));
    }

    /**
     * 争议详情（含双方证据）。
     *
     * @param current   会话主体
     * @param disputeId 争议单 ID
     * @return 详情
     */
    @GetMapping("/api/disputes/{disputeId}")
    public ApiResponse<DisputeView> detail(@AuthenticationPrincipal LoginUser current,
                                           @PathVariable Long disputeId) {
        return ApiResponse.ok(disputeService.detail(current, disputeId));
    }

    /**
     * 提交证据（FR-DISPUTE.EVIDENCE.01）。
     *
     * @param current   会话主体
     * @param disputeId 争议单 ID
     * @param request   证据内容
     * @return 新证据
     */
    @PostMapping("/api/disputes/{disputeId}/evidence")
    public ApiResponse<DisputeEvidenceView> addEvidence(
            @AuthenticationPrincipal LoginUser current,
            @PathVariable Long disputeId,
            @Valid @RequestBody DisputeEvidenceRequest request) {
        return ApiResponse.ok(disputeService.addEvidence(current, disputeId, request));
    }

    /**
     * 从会话取证（V11，补遗 §5.4）：把聊天消息复制为争议证据。
     *
     * <p>只读聊天表，不写消息；调用者必须是争议当事人。</p>
     *
     * @param current   会话主体
     * @param disputeId 争议单 ID
     * @param request   消息 ID 列表
     * @return 新证据列表
     */
    @PostMapping("/api/disputes/{disputeId}/evidence/from-chat")
    public ApiResponse<List<DisputeEvidenceView>> addEvidenceFromChat(
            @AuthenticationPrincipal LoginUser current,
            @PathVariable Long disputeId,
            @RequestBody(required = false) ChatEvidenceRequest request) {
        return ApiResponse.ok(chatEvidenceService.copyFromChat(current, disputeId, request));
    }

    /**
     * 撤回争议（FR-DISPUTE.WITHDRAW.01）。
     *
     * @param current   会话主体
     * @param disputeId 争议单 ID
     * @return 成功响应
     */
    @PostMapping("/api/disputes/{disputeId}/withdraw")
    public ApiResponse<Void> withdraw(@AuthenticationPrincipal LoginUser current,
                                      @PathVariable Long disputeId) {
        disputeResolutionService.withdraw(current, disputeId);
        return ApiResponse.ok(null);
    }
}
