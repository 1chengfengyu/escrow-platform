package com.escrow.platform.engagement.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.engagement.api.ReputationView;
import com.escrow.platform.engagement.api.ReviewCreateRequest;
import com.escrow.platform.engagement.api.ReviewView;
import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.engagement.domain.EngagementContract;
import com.escrow.platform.engagement.domain.EngagementPerformanceRecord;
import com.escrow.platform.engagement.domain.EngagementReview;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementContractMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementPerformanceRecordMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementReviewMapper;
import com.escrow.platform.identity.api.UserCardResponse;
import com.escrow.platform.identity.application.IdentityProfileService;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.application.TaskQueryService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 签约评价与信誉测试（V7，FR-SIGN.REVIEW.01 / FR-REPUTATION.01）。
 *
 * @since V7
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    private static final LoginUser WORKER =
            new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final LoginUser EMPLOYER =
            new LoginUser(2001L, "employer@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final LoginUser STRANGER =
            new LoginUser(3001L, "stranger@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final Long CONTRACT_ID = 7001L;
    private static final Long TASK_ID = 5001L;

    @Mock
    private EngagementContractMapper contractMapper;
    @Mock
    private EngagementPerformanceRecordMapper performanceRecordMapper;
    @Mock
    private EngagementReviewMapper reviewMapper;
    @Mock
    private TaskQueryService taskQueryService;
    @Mock
    private IdentityProfileService identityProfileService;

    @InjectMocks
    private ReviewService service;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, EngagementContract.class);
        TableInfoHelper.initTableInfo(assistant, EngagementPerformanceRecord.class);
        TableInfoHelper.initTableInfo(assistant, EngagementReview.class);
    }

    /** 未登录：401。 */
    @Test
    void anonymousShouldBeRejected() {
        assertThatThrownBy(() -> service.create(null, CONTRACT_ID, new ReviewCreateRequest(5, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    /** 签约不存在：404。 */
    @Test
    void missingContractShouldBeNotFound() {
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.create(WORKER, CONTRACT_ID, new ReviewCreateRequest(5, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 非当事人：403。 */
    @Test
    void strangerShouldBeForbidden() {
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(contract(ContractStatus.CONTRACT_COMPLETED));
        when(taskQueryService.employerIdOf(TASK_ID)).thenReturn(EMPLOYER.userId());

        assertThatThrownBy(() -> service.create(STRANGER, CONTRACT_ID, new ReviewCreateRequest(5, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    /** 未完成签约：409。 */
    @Test
    void nonCompletedContractShouldBeRejected() {
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(contract(ContractStatus.CONTRACT_DELIVERED));
        when(taskQueryService.employerIdOf(TASK_ID)).thenReturn(EMPLOYER.userId());

        assertThatThrownBy(() -> service.create(WORKER, CONTRACT_ID, new ReviewCreateRequest(5, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        verify(reviewMapper, never()).insert(any(EngagementReview.class));
    }

    /** 评分越界：400。 */
    @Test
    void invalidRatingShouldBeRejected() {
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(contract(ContractStatus.CONTRACT_COMPLETED));
        when(taskQueryService.employerIdOf(TASK_ID)).thenReturn(EMPLOYER.userId());

        assertThatThrownBy(() -> service.create(WORKER, CONTRACT_ID, new ReviewCreateRequest(6, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    /** 评价内容超长：400。 */
    @Test
    void tooLongCommentShouldBeRejected() {
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(contract(ContractStatus.CONTRACT_COMPLETED));
        when(taskQueryService.employerIdOf(TASK_ID)).thenReturn(EMPLOYER.userId());

        assertThatThrownBy(() -> service.create(WORKER, CONTRACT_ID,
                new ReviewCreateRequest(5, "x".repeat(201))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        verify(reviewMapper, never()).insert(any(EngagementReview.class));
    }

    /** 重复评价（唯一键冲突）：409。 */
    @Test
    void duplicateReviewShouldConflict() {
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(contract(ContractStatus.CONTRACT_COMPLETED));
        when(taskQueryService.employerIdOf(TASK_ID)).thenReturn(EMPLOYER.userId());
        when(reviewMapper.insert(any(EngagementReview.class))).thenThrow(new DuplicateKeyException("uk"));

        assertThatThrownBy(() -> service.create(WORKER, CONTRACT_ID, new ReviewCreateRequest(5, "很好")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    /** 劳动者评雇主：被评价人落雇主 ID，返回评价人昵称。 */
    @Test
    void workerReviewShouldTargetEmployer() {
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(contract(ContractStatus.CONTRACT_COMPLETED));
        when(taskQueryService.employerIdOf(TASK_ID)).thenReturn(EMPLOYER.userId());
        when(identityProfileService.nicknames(any())).thenReturn(Map.of(WORKER.userId(), "打工人"));
        when(reviewMapper.insert(any(EngagementReview.class))).thenReturn(1);

        ReviewView view = service.create(WORKER, CONTRACT_ID, new ReviewCreateRequest(5, "  结算及时  "));

        ArgumentCaptor<EngagementReview> captor = ArgumentCaptor.forClass(EngagementReview.class);
        verify(reviewMapper).insert(captor.capture());
        assertThat(captor.getValue().getReviewerId()).isEqualTo(WORKER.userId());
        assertThat(captor.getValue().getRevieweeId()).isEqualTo(EMPLOYER.userId());
        assertThat(captor.getValue().getRating()).isEqualTo(5);
        assertThat(captor.getValue().getComment()).isEqualTo("结算及时");
        assertThat(view.reviewerNickname()).isEqualTo("打工人");
    }

    /** 雇主评劳动者：被评价人落劳动者 ID。 */
    @Test
    void employerReviewShouldTargetWorker() {
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(contract(ContractStatus.CONTRACT_COMPLETED));
        when(taskQueryService.employerIdOf(TASK_ID)).thenReturn(EMPLOYER.userId());
        when(identityProfileService.nicknames(any())).thenReturn(Map.of(EMPLOYER.userId(), "雇主甲"));
        when(reviewMapper.insert(any(EngagementReview.class))).thenReturn(1);

        service.create(EMPLOYER, CONTRACT_ID, new ReviewCreateRequest(4, null));

        ArgumentCaptor<EngagementReview> captor = ArgumentCaptor.forClass(EngagementReview.class);
        verify(reviewMapper).insert(captor.capture());
        assertThat(captor.getValue().getRevieweeId()).isEqualTo(WORKER.userId());
        assertThat(captor.getValue().getComment()).isNull();
    }

    /** 信誉：完成数、超时率、平均分与评价分页。 */
    @Test
    void reputationShouldAggregateCounts() {
        when(identityProfileService.card(WORKER.userId()))
                .thenReturn(new UserCardResponse(WORKER.userId(), "打工人", null, null, "WORKER"));
        when(contractMapper.selectCount(any())).thenReturn(8L);
        when(performanceRecordMapper.selectCount(any())).thenReturn(2L);
        when(reviewMapper.selectCount(any())).thenReturn(3L);
        when(reviewMapper.selectObjs(any())).thenReturn(List.of(5, 4, 4));
        EngagementReview review = new EngagementReview();
        review.setId(1L);
        review.setReviewerId(EMPLOYER.userId());
        review.setRevieweeId(WORKER.userId());
        review.setRating(5);
        review.setComment("不错");
        review.setCreatedAt(LocalDateTime.of(2026, 11, 18, 9, 0));
        Page<EngagementReview> page = new Page<>(1, 10);
        page.setRecords(List.of(review));
        page.setTotal(3);
        when(reviewMapper.selectPage(any(), any())).thenReturn(page);
        when(identityProfileService.nicknames(any())).thenReturn(Map.of(EMPLOYER.userId(), "雇主甲"));

        ReputationView view = service.reputation(WORKER.userId(), 1, 10);

        assertThat(view.completedContracts()).isEqualTo(8L);
        assertThat(view.timeoutCancels()).isEqualTo(2L);
        assertThat(view.timeoutRateBps()).isEqualTo(2_000);
        assertThat(view.averageRatingX100()).isEqualTo(433);
        assertThat(view.reviewCount()).isEqualTo(3L);
        assertThat(view.reviews().items()).hasSize(1);
        assertThat(view.reviews().items().get(0).reviewerNickname()).isEqualTo("雇主甲");
    }

    /** 无完成、无超时：超时率按 0 处理，不除零。 */
    @Test
    void zeroDenominatorShouldYieldZeroRate() {
        when(identityProfileService.card(anyLong()))
                .thenReturn(new UserCardResponse(WORKER.userId(), "打工人", null, null, "WORKER"));
        when(contractMapper.selectCount(any())).thenReturn(0L);
        when(performanceRecordMapper.selectCount(any())).thenReturn(0L);
        when(reviewMapper.selectCount(any())).thenReturn(0L);
        when(reviewMapper.selectObjs(any())).thenReturn(List.of());
        Page<EngagementReview> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(reviewMapper.selectPage(any(), any())).thenReturn(page);

        ReputationView view = service.reputation(WORKER.userId(), 1, 10);

        assertThat(view.timeoutRateBps()).isZero();
        assertThat(view.averageRatingX100()).isZero();
        assertThat(view.reviews().items()).isEmpty();
    }

    private static EngagementContract contract(ContractStatus status) {
        EngagementContract contract = new EngagementContract();
        contract.setId(CONTRACT_ID);
        contract.setTaskId(TASK_ID);
        contract.setWorkerId(WORKER.userId());
        contract.setStatus(status);
        return contract;
    }
}

