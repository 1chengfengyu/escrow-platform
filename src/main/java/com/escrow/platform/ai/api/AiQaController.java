package com.escrow.platform.ai.api;

import com.escrow.platform.ai.application.AiQaService;
import com.escrow.platform.ai.application.AiResults;
import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规则问答入口（V10-c5）：雇主与服务者都可用，答案带强制引用。
 *
 * @since V10
 */
@RestController
@RequestMapping("/api/ai/qa")
@RequiredArgsConstructor
public class AiQaController {

    private final AiQaService qaService;

    /** 提问；无依据时返回弃答文案而不是编造答案。 */
    @PostMapping
    public ApiResponse<AiResults.Qa> ask(@RequestBody QaRequest request,
                                         @AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(qaService.ask(current, request.question()));
    }

    /** 提问请求。 */
    public record QaRequest(String question) {
    }
}
