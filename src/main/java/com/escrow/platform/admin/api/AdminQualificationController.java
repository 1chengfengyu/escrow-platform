package com.escrow.platform.admin.api;

import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.identity.api.QualificationListItem;
import com.escrow.platform.identity.api.QualificationReviewRequest;
import com.escrow.platform.identity.application.QualificationReviewService;
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

import java.util.List;

/**
 * 管理端资质审核入口（FR-ADMIN.QUAL.01）。
 *
 * <p>放在 {@code admin/api} 而不是 {@code identity/api}：URL 属于 D7 治理面，
 * 但实现只调用 identity 域的 application 入口（{@link QualificationReviewService}），
 * 不碰 identity 的 Mapper——符合「域间只能调对方 Service」这条规则（V8 拆服务时的切分线）。</p>
 *
 * <p>ADMIN 身份校验在 application 层：Controller 拿不到「当前身份够不够」这个业务结论，
 * 它也必须在服务被第二个入口调用时同样成立。</p>
 *
 * @since V1
 */
@RestController
@RequestMapping("/api/admin/qualifications")
@RequiredArgsConstructor
public class AdminQualificationController {

    private final QualificationReviewService qualificationReviewService;

    /**
     * 按状态列出资质，缺省 {@code PENDING}；固定 50 条、按提交时间升序。
     *
     * @param status  筛选状态（PENDING / APPROVED / REJECTED），可空
     * @param current 会话主体，必须是 ADMIN
     */
    @GetMapping
    public ApiResponse<List<QualificationListItem>> list(
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(qualificationReviewService.list(current, status));
    }

    /**
     * 审核某用户的资质：需要 {@code decision}（APPROVED / REJECTED），驳回时必填 {@code reason}。
     *
     * @param userId  目标账号 ID
     * @param request 审核请求
     * @param current 会话主体，必须是 ADMIN
     */
    @PostMapping("/{userId}/review")
    public ApiResponse<Void> review(@PathVariable Long userId,
                                    @RequestBody QualificationReviewRequest request,
                                    @AuthenticationPrincipal LoginUser current) {
        qualificationReviewService.review(current, userId, request);
        return ApiResponse.ok(null);
    }
}
