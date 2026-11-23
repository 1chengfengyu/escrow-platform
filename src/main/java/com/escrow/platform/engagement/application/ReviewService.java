package com.escrow.platform.engagement.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.engagement.api.ReputationView;
import com.escrow.platform.engagement.api.ReviewCreateRequest;
import com.escrow.platform.engagement.api.ReviewView;
import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.engagement.domain.EngagementContract;
import com.escrow.platform.engagement.domain.EngagementPerformanceRecord;
import com.escrow.platform.engagement.domain.EngagementReview;
import com.escrow.platform.engagement.domain.PerformanceEventType;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementContractMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementPerformanceRecordMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementReviewMapper;
import com.escrow.platform.identity.application.IdentityProfileService;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.task.application.TaskQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 签约评价与劳动者信誉（V7，FR-REPUT.RATE.01 / FR-REPUT.RATE.02；信誉页为评价的派生视图）。
 *
 * <p>评价的唯一性靠 {@code uk_engagement_review_contract_reviewer}：
 * INSERT 撞键即「一份签约每人只能评一次」，捕获后返回 409，
 * 不做「先 SELECT 判断再 INSERT」（project_rules §6）。</p>
 *
 * <p>雇主身份不落在签约表：{@code employerId} 通过 {@code TaskQueryService#employerIdOf}
 * 这个跨域只读入口取得，不直接查 {@code task} 表。</p>
 *
 * @since V7
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_COMMENT_LENGTH = 200;
    private static final int RATING_MIN = 1;
    private static final int RATING_MAX = 5;

    private static final String UNAUTHENTICATED_MESSAGE = "请先登录";
    private static final String CONTRACT_NOT_FOUND_MESSAGE = "签约不存在";
    private static final String NOT_PARTY_MESSAGE = "只有签约当事人可以评价";
    private static final String NOT_COMPLETED_MESSAGE = "只有已完成的签约可以评价";
    private static final String RATING_INVALID_MESSAGE = "评分必须是 1~5 的整数";
    private static final String COMMENT_TOO_LONG_MESSAGE = "评价内容不能超过 " + MAX_COMMENT_LENGTH + " 字";
    private static final String DUPLICATE_MESSAGE = "该签约已评价过";

    private final EngagementContractMapper contractMapper;
    private final EngagementPerformanceRecordMapper performanceRecordMapper;
    private final EngagementReviewMapper reviewMapper;
    private final TaskQueryService taskQueryService;
    private final IdentityProfileService identityProfileService;

    /**
     * 创建一条签约评价（FR-REPUT.RATE.01 / FR-REPUT.RATE.02）。
     *
     * @param current    会话主体
     * @param contractId 签约 ID
     * @param request    评分与文字评价
     * @return 新评价视图
     * @throws ApiException 401 未登录 / 403 非当事人 / 404 签约不存在 / 400 评分或内容不合法
     *                      / 409 签约未完成或已评价
     */
    @Transactional
    public ReviewView create(LoginUser current, Long contractId, ReviewCreateRequest request) {
        if (current == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_MESSAGE);
        }
        EngagementContract contract = contractMapper.selectById(contractId);
        if (contract == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, CONTRACT_NOT_FOUND_MESSAGE);
        }
        Long employerId = taskQueryService.employerIdOf(contract.getTaskId());
        boolean reviewerIsWorker = contract.getWorkerId().equals(current.userId());
        boolean reviewerIsEmployer = employerId.equals(current.userId());
        if (!reviewerIsWorker && !reviewerIsEmployer) {
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_PARTY_MESSAGE);
        }
        if (contract.getStatus() != ContractStatus.CONTRACT_COMPLETED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, NOT_COMPLETED_MESSAGE);
        }

        int rating = requireRating(request == null ? null : request.rating());
        String comment = normalizeComment(request == null ? null : request.comment());
        Long revieweeId = reviewerIsWorker ? employerId : contract.getWorkerId();

        EngagementReview review = new EngagementReview();
        review.setContractId(contractId);
        review.setReviewerId(current.userId());
        review.setRevieweeId(revieweeId);
        review.setRating(rating);
        review.setComment(comment);
        review.setCreatedAt(LocalDateTime.now());
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.CONFLICT, DUPLICATE_MESSAGE);
        }

        log.info("签约评价 bizId={} reviewer={} reviewee={} rating={}",
                contractId, current.userId(), revieweeId, rating);
        Map<Long, String> nicknames = identityProfileService.nicknames(List.of(current.userId()));
        return new ReviewView(current.userId(), nicknames.getOrDefault(current.userId(), ""),
                rating, comment, review.getCreatedAt());
    }

    /**
     * 查看用户信誉（FR-REPUT.RATE.01 的派生视图）。
     *
     * <p>超时率口径：超时取消次数 /（已完成签约数 + 超时取消次数）；
     * 分母为 0 时按 0 处理。平均分用「评分总和 × 100 / 评价数」四舍五入到整数。</p>
     *
     * @param userId 目标账号 ID
     * @param page   评价页码，从 1 开始
     * @param size   每页条数（1~{@value #MAX_PAGE_SIZE}）
     * @return 信誉视图
     * @throws ApiException 404 目标账号不存在
     */
    public ReputationView reputation(Long userId, int page, int size) {
        // 走 identity 的只读入口校验存在性，不直查 platform_user
        identityProfileService.card(userId);

        long completed = count(contractMapper.selectCount(new LambdaQueryWrapper<EngagementContract>()
                .eq(EngagementContract::getWorkerId, userId)
                .eq(EngagementContract::getStatus, ContractStatus.CONTRACT_COMPLETED)));
        long timeoutCancels = count(performanceRecordMapper.selectCount(
                new LambdaQueryWrapper<EngagementPerformanceRecord>()
                        .eq(EngagementPerformanceRecord::getWorkerId, userId)
                        .eq(EngagementPerformanceRecord::getEventType,
                                PerformanceEventType.PERFORMANCE_TIMEOUT_CANCEL)));
        long denominator = completed + timeoutCancels;
        int timeoutRateBps = denominator == 0 ? 0
                : (int) (timeoutCancels * 10_000 / denominator);

        long reviewCount = count(reviewMapper.selectCount(new LambdaQueryWrapper<EngagementReview>()
                .eq(EngagementReview::getRevieweeId, userId)));
        long ratingSum = sumRatings(userId);
        int averageRatingX100 = reviewCount == 0 ? 0
                : (int) ((ratingSum * 100 + reviewCount / 2) / reviewCount);

        int pageNo = Math.max(1, page);
        int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        Page<EngagementReview> reviewPage = reviewMapper.selectPage(
                new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<EngagementReview>()
                        .eq(EngagementReview::getRevieweeId, userId)
                        .orderByDesc(EngagementReview::getCreatedAt)
                        .orderByDesc(EngagementReview::getId));
        List<ReviewView> reviews = toViews(reviewPage.getRecords());

        return new ReputationView(userId, completed, timeoutCancels, timeoutRateBps,
                averageRatingX100, reviewCount,
                PageView.of(reviews, pageNo, pageSize, reviewPage.getTotal()));
    }

    private long sumRatings(Long userId) {
        List<Object> ratings = reviewMapper.selectObjs(new LambdaQueryWrapper<EngagementReview>()
                .select(EngagementReview::getRating)
                .eq(EngagementReview::getRevieweeId, userId));
        long sum = 0L;
        for (Object value : ratings) {
            if (value instanceof Number number) {
                sum += number.longValue();
            }
        }
        return sum;
    }

    private List<ReviewView> toViews(List<EngagementReview> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> nicknames = identityProfileService.nicknames(
                rows.stream().map(EngagementReview::getReviewerId).distinct().toList());
        return rows.stream()
                .map(row -> new ReviewView(row.getReviewerId(),
                        nicknames.getOrDefault(row.getReviewerId(), ""),
                        row.getRating(), row.getComment(), row.getCreatedAt()))
                .toList();
    }

    private static int requireRating(Integer rating) {
        if (rating == null || rating < RATING_MIN || rating > RATING_MAX) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, RATING_INVALID_MESSAGE);
        }
        return rating;
    }

    private static String normalizeComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String trimmed = comment.trim();
        if (trimmed.length() > MAX_COMMENT_LENGTH) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, COMMENT_TOO_LONG_MESSAGE);
        }
        return trimmed;
    }

    private static long count(Long value) {
        return value == null ? 0L : value;
    }
}
