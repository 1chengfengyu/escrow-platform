package com.escrow.platform.engagement.api;

import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.engagement.application.ReviewService;
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
 * 评价与信誉入口（V7，FR-REPUT.RATE.01 / FR-REPUT.RATE.02；信誉页为评价的派生视图）。
 *
 * <p>路径沿用既有的 {@code /api/contracts} 与 {@code /api/users} 资源语义，
 * 不再新建一套前缀；归属与状态判断全部在 {@link ReviewService}。</p>
 *
 * @since V7
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    /**
     * 对已完成的签约发表评价（劳动者 ↔ 雇主各一次）。
     *
     * @param contractId 签约 ID
     * @param request    评分 1~5 与可选文字评价
     * @param current    会话主体
     * @return 新评价
     */
    @PostMapping("/contracts/{contractId}/reviews")
    public ApiResponse<ReviewView> create(@PathVariable Long contractId,
                                          @RequestBody ReviewCreateRequest request,
                                          @AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(reviewService.create(current, contractId, request));
    }

    /**
     * 查看用户信誉与收到的评价。
     *
     * @param userId 目标账号 ID
     * @param page   评价页码，从 1 开始
     * @param size   每页条数
     * @return 信誉视图
     */
    @GetMapping("/users/{userId}/reputation")
    public ApiResponse<ReputationView> reputation(@PathVariable Long userId,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(reviewService.reputation(userId, page, size));
    }
}
