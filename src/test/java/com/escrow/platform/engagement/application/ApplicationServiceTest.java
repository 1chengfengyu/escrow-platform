package com.escrow.platform.engagement.application;

import com.escrow.platform.common.chat.application.ChatEventDispatcher;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.engagement.domain.ApplicationStatus;
import com.escrow.platform.engagement.domain.EngagementApplication;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementApplicationMapper;
import com.escrow.platform.identity.application.IdentityProfileService;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.application.TaskParticipationService;
import com.escrow.platform.task.application.TaskParticipationService.ParticipationFacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 报名守卫单元测试（FR-SIGN.APPLY.01~.05）。
 *
 * <p>只锁「守卫顺序与错误码」，不证明并发：重复报名只可能有一笔成功必须由
 * 真库唯一索引证明，见 {@code V3ApplicationConcurrencyIntegrationTest}。</p>
 *
 * @since V3
 */
@ExtendWith(MockitoExtension.class)
class ApplicationServiceTest {

    private static final long TASK_ID = 10L;
    private static final long EMPLOYER_ID = 1001L;
    private static final LoginUser WORKER =
            new LoginUser(2001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final LoginUser EMPLOYER =
            new LoginUser(EMPLOYER_ID, "employer@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);

    @Mock
    private EngagementApplicationMapper applicationMapper;

    @Mock
    private TaskParticipationService taskParticipationService;

    @Mock
    private IdentityProfileService identityProfileService;

    @Mock
    private ChatEventDispatcher chatEventDispatcher;

    @InjectMocks
    private ApplicationService applicationService;

    /** FR-SIGN.APPLY.01/.04：成功报名只落一条待处理记录，不碰名额。 */
    @Test
    void applyShouldCreatePendingApplicationWithoutOccupyingSlot() {
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(publishedTask(1));
        when(applicationMapper.insert(any(EngagementApplication.class))).thenAnswer(invocation -> {
            invocation.<EngagementApplication>getArgument(0).setId(99L);
            return 1;
        });

        Long id = applicationService.apply(WORKER, TASK_ID);

        assertThat(id).isEqualTo(99L);
        ArgumentCaptor<EngagementApplication> captor =
                ArgumentCaptor.forClass(EngagementApplication.class);
        verify(applicationMapper).insert(captor.capture());
        EngagementApplication saved = captor.getValue();
        assertThat(saved.getTaskId()).isEqualTo(TASK_ID);
        assertThat(saved.getWorkerId()).isEqualTo(WORKER.userId());
        assertThat(saved.getStatus()).isEqualTo(ApplicationStatus.APPLY_PENDING);
        assertThat(saved.getStatus().isActive()).isTrue();
        // 报名不占名额：本卡没有任何名额写入入口
        verifyNoInteractions(identityProfileService);
        // CARD-10：报名成功后登记 APPLICATION 会话事件（雇主 = 任务发布者）
        verify(chatEventDispatcher).applicationCreated(TASK_ID, 99L, EMPLOYER_ID, WORKER.userId());
    }

    /** 当前身份不是劳动者：403，且不读任务。 */
    @Test
    void applyShouldRejectNonWorker() {
        ApiException ex = catchThrowableOfType(() -> applicationService.apply(EMPLOYER, TASK_ID),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(taskParticipationService, applicationMapper);
    }

    /** FR-SIGN.APPLY.05 / CR-15：不能报名自己发布的任务。 */
    @Test
    void applyShouldRejectSelfApplication() {
        LoginUser selfEmployer = new LoginUser(EMPLOYER_ID, "self@example.com",
                UserRole.WORKER, IdentityType.WORKER);
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(publishedTask(1));

        ApiException ex = catchThrowableOfType(() -> applicationService.apply(selfEmployer, TASK_ID),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SELF_APPLICATION_FORBIDDEN);
        verify(applicationMapper, never()).insert(any(EngagementApplication.class));
    }

    /** 草稿对非雇主不可见：404，不暴露任务存在性。 */
    @Test
    void applyShouldRejectDraftAsNotFound() {
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(
                new ParticipationFacts(TASK_ID, EMPLOYER_ID, null, null,
                        LocalDateTime.now().plusDays(1), 1));

        ApiException ex = catchThrowableOfType(() -> applicationService.apply(WORKER, TASK_ID),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(applicationMapper, never()).insert(any(EngagementApplication.class));
    }

    /** 已撤单：409。 */
    @Test
    void applyShouldRejectCancelledTask() {
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(
                new ParticipationFacts(TASK_ID, EMPLOYER_ID, LocalDateTime.now().minusDays(2),
                        LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1), 1));

        ApiException ex = catchThrowableOfType(() -> applicationService.apply(WORKER, TASK_ID),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        verify(applicationMapper, never()).insert(any(EngagementApplication.class));
    }

    /** 已过结束时间：409（守卫直接判时间，不看展示状态）。 */
    @Test
    void applyShouldRejectEndedTask() {
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(
                new ParticipationFacts(TASK_ID, EMPLOYER_ID, LocalDateTime.now().minusDays(2),
                        null, LocalDateTime.now().minusMinutes(1), 1));

        ApiException ex = catchThrowableOfType(() -> applicationService.apply(WORKER, TASK_ID),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        verify(applicationMapper, never()).insert(any(EngagementApplication.class));
    }

    /** FR-SIGN.APPLY.02：剩余名额为 0 → NO_SLOT_AVAILABLE。 */
    @Test
    void applyShouldRejectWhenNoSlotAvailable() {
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(publishedTask(0));

        ApiException ex = catchThrowableOfType(() -> applicationService.apply(WORKER, TASK_ID),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NO_SLOT_AVAILABLE);
        verify(applicationMapper, never()).insert(any(EngagementApplication.class));
    }

    /** FR-SIGN.APPLY.03：唯一索引命中 → ALREADY_APPLIED，不把并发冲突当 500。 */
    @Test
    void applyShouldMapDuplicateKeyToAlreadyApplied() {
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(publishedTask(1));
        when(applicationMapper.insert(any(EngagementApplication.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_active"));

        assertThatThrownBy(() -> applicationService.apply(WORKER, TASK_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_APPLIED);
    }

    private static ParticipationFacts publishedTask(long availableSlots) {
        return new ParticipationFacts(TASK_ID, EMPLOYER_ID, LocalDateTime.now().minusDays(1),
                null, LocalDateTime.now().plusDays(1), availableSlots);
    }
}
