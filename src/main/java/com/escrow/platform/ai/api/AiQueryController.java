package com.escrow.platform.ai.api;

import com.escrow.platform.ai.application.AiAccessGuard;
import com.escrow.platform.ai.application.AiQueryUnderstandingService;
import com.escrow.platform.ai.application.AiResults;
import com.escrow.platform.ai.application.AiSearchService;
import com.escrow.platform.ai.application.AiTaskSearchService;
import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 找人入口（V10-c2 / c4）：先解析、确认、再检索。
 *
 * <p>两个端点分开不是多此一举：解析只产出可编辑标签，检索必须带 {@code draftId}，
 * 用户改过标签才检索——这是"理解错误可被当场拦下"的落点。</p>
 *
 * <p>检索只对雇主开放（D5）；{@code userId} 从会话取，请求体里没有这个字段。</p>
 *
 * @since V10
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiQueryController {

    private final AiQueryUnderstandingService understandingService;
    private final AiSearchService searchService;
    private final AiTaskSearchService taskSearchService;
    private final AiAccessGuard accessGuard;

    /** 口语需求 → 槽位标签（不检索）。 */
    @PostMapping("/query/parse")
    public ApiResponse<AiResults.Understand> parse(@RequestBody ParseRequest request,
                                                   @AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(understandingService.parse(current, request.query()));
    }

    /** 确认后的槽位 → 候选人列表。 */
    @PostMapping("/search")
    public ApiResponse<AiResults.Search> search(@RequestBody SearchRequest request,
                                                @AuthenticationPrincipal LoginUser current) {
        accessGuard.requireEmployer(current);
        return ApiResponse.ok(searchService.search(current, request.draftId(), request.removedKeys()));
    }

    /**
     * 确认后的槽位 → 可接任务列表（劳动者侧，V12.1）。
     *
     * <p>与 {@code /search} 共用草稿：槽位语义在服务内反转，请求体不变。</p>
     */
    @PostMapping("/search/tasks")
    public ApiResponse<AiResults.TaskSearch> searchTasks(@RequestBody SearchRequest request,
                                                         @AuthenticationPrincipal LoginUser current) {
        accessGuard.requireWorker(current);
        return ApiResponse.ok(taskSearchService.search(current, request.draftId(), request.removedKeys()));
    }

    /** 解析请求。 */
    public record ParseRequest(String query) {
    }

    /** 检索请求：草稿ID + 用户在标签上删掉的槽位键。 */
    public record SearchRequest(String draftId, List<String> removedKeys) {

        public SearchRequest {
            removedKeys = removedKeys == null ? List.of() : List.copyOf(removedKeys);
        }
    }
}
