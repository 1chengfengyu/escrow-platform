package com.escrow.platform.ai.api;

import com.escrow.platform.ai.application.AiFeedbackService;
import com.escrow.platform.ai.application.AiResults;
import com.escrow.platform.ai.application.AiTraceService;
import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 链路回放与反馈入口（V10-c6 / c7）。
 *
 * <p>traceNo 与日志里的 traceId 是同一个值，因此"用户说结果不对"时，
 * 用日志里的号就能直接回放出当时的阶段与模型调用明细。</p>
 *
 * <p>反馈幂等由唯一键保证：同一条链路重复上报只置位、不累加。</p>
 *
 * @since V10
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiTraceController {

    private final AiTraceService traceService;
    private final AiFeedbackService feedbackService;

    /** 按链路号回放；只能看自己的链路。 */
    @GetMapping("/trace/{traceNo}")
    public ApiResponse<AiResults.Trace> replay(@PathVariable String traceNo,
                                               @AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(traceService.replay(traceNo, current.userId()));
    }

    /** 上报点击 / 二次搜索。 */
    @PostMapping("/feedback")
    public ApiResponse<Void> feedback(@RequestBody FeedbackRequest request,
                                      @AuthenticationPrincipal LoginUser current) {
        feedbackService.record(current, request.traceNo(),
                Boolean.TRUE.equals(request.clicked()), Boolean.TRUE.equals(request.secondSearch()));
        return ApiResponse.ok(null);
    }

    /** 反馈请求。 */
    public record FeedbackRequest(String traceNo, Boolean clicked, Boolean secondSearch) {
    }
}
