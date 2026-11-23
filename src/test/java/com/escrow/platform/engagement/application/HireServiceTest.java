package com.escrow.platform.engagement.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.common.chat.application.ChatEventDispatcher;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.engagement.domain.ApplicationStatus;
import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.engagement.domain.EngagementApplication;
import com.escrow.platform.engagement.domain.EngagementContract;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementApplicationMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementContractMapper;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.application.TaskParticipationService;
import com.escrow.platform.task.application.TaskParticipationService.ParticipationFacts;
import com.escrow.platform.task.application.TaskSlotOccupancyService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 录用服务单元测试（v3-c2，FR-SIGN.HIRE.01~.04 / FR-SIGN.APPLY.08）。
 *
 * <p>只锁守卫顺序、错误码与「事务内写三处」的调用编排；并发只允许一个成功
 * 必须由真库用例 {@code V3HireConcurrencyIntegrationTest} 证明。</p>
 *
 * @since V3
 */
@ExtendWith(MockitoExtension.class)
class HireServiceTest {

    private static final long TASK_ID = 10L;
    private static final long EMPLOYER_ID = 1001L;
    private static final long APPLICATION_ID = 3001L;
    private static final long WORKER_ID = 2001L;
    private static final long SLOT_ID = 21L;
    private static final long CONTRACT_ID = 5001L;

    private static final LoginUser EMPLOYER =
            new LoginUser(EMPLOYER_ID, "employer@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final LoginUser WORKER =
            new LoginUser(WORKER_ID, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Mock
    private EngagementApplicationMapper applicationMapper;

    @Mock
    private EngagementContractMapper contractMapper;

    @Mock
    private TaskParticipationService taskParticipationService;

    @Mock
    private TaskSlotOccupancyService taskSlotOccupancyService;

    @Mock
    private ApplicationRejectionService applicationRejectionService;

    @Mock
    private ChatEventDispatcher chatEventDispatcher;

    @InjectMocks
    private HireService hireService;

    /** 条件更新用 Lambda 构造器，纯单测需先初始化 {@code TableInfo} 缓存。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, EngagementApplication.class);
        TableInfoHelper.initTableInfo(assistant, EngagementContract.class);
    }

    /** FR-SIGN.HIRE.01：报名转已录用 → 占名额 → 建生效中签约 → 回填名额签约 ID。 */
    @Test
    void hireShouldClaimApplicationOccupySlotAndCreateContract() {
        when(taskParticipationService.lockFactsOf(TASK_ID)).thenReturn(publishedTask(null, 2));
        when(applicationMapper.selectById(APPLICATION_ID)).thenReturn(pendingApplication(WORKER_ID));
        when(applicationMapper.update(any(), any())).thenReturn(1);
        when(taskSlotOccupancyService.occupyForHire(TASK_ID, APPLICATION_ID)).thenReturn(SLOT_ID);
        when(contractMapper.insert(any(EngagementContract.class))).thenAnswer(invocation -> {
            invocation.<EngagementContract>getArgument(0).setId(CONTRACT_ID);
            return 1;
        });
        when(taskSlotOccupancyService.hasAvailableSlot(TASK_ID)).thenReturn(true);

        Long contractId = hireService.hire(EMPLOYER, TASK_ID, APPLICATION_ID);

        assertThat(contractId).isEqualTo(CONTRACT_ID);
        ArgumentCaptor<EngagementContract> captor = ArgumentCaptor.forClass(EngagementContract.class);
        verify(contractMapper).insert(captor.capture());
        EngagementContract saved = captor.getValue();
        assertThat(saved.getTaskId()).isEqualTo(TASK_ID);
        assertThat(saved.getSlotId()).isEqualTo(SLOT_ID);
        assertThat(saved.getApplicationId()).isEqualTo(APPLICATION_ID);
        assertThat(saved.getWorkerId()).isEqualTo(WORKER_ID);
        assertThat(saved.getStatus()).isEqualTo(ContractStatus.CONTRACT_ACTIVE);
        assertThat(saved.getStatus().isActive()).isTrue();
        assertThat(saved.getEffectiveAt()).isNotNull();
        assertThat(saved.getStartedAt()).isNull();
        assertThat(saved.getDeliveredAt()).isNull();
        verify(taskSlotOccupancyService).bindContract(SLOT_ID, CONTRACT_ID);
        // CARD-10：签约成功后登记 CONTRACT 会话事件
        verify(chatEventDispatcher).contractCreated(TASK_ID, CONTRACT_ID, EMPLOYER_ID, WORKER_ID);
        // 仍有待占用名额：不收口，也不写 recruiting_closed
        verify(applicationRejectionService, never()).rejectPendingOfTask(anyLong());
    }

    /** FR-SIGN.HIRE.02 + APPLY.08：占完最后一个名额后批量拒绝待处理报名。 */
    @Test
    void hireShouldRejectPendingApplicationsWhenFull() {
        when(taskParticipationService.lockFactsOf(TASK_ID)).thenReturn(publishedTask(null, 1));
        when(applicationMapper.selectById(APPLICATION_ID)).thenReturn(pendingApplication(WORKER_ID));
        when(applicationMapper.update(any(), any())).thenReturn(1);
        when(taskSlotOccupancyService.occupyForHire(TASK_ID, APPLICATION_ID)).thenReturn(SLOT_ID);
        when(contractMapper.insert(any(EngagementContract.class))).thenAnswer(invocation -> {
            invocation.<EngagementContract>getArgument(0).setId(CONTRACT_ID);
            return 1;
        });
        when(taskSlotOccupancyService.hasAvailableSlot(TASK_ID)).thenReturn(false);

        assertThat(hireService.hire(EMPLOYER, TASK_ID, APPLICATION_ID)).isEqualTo(CONTRACT_ID);

        verify(applicationRejectionService).rejectPendingOfTask(TASK_ID);
    }

    /** FR-SIGN.HIRE.04 / CR-15：不能录用自己（发布者本人报名了同一任务）。 */
    @Test
    void hireShouldRejectSelfHire() {
        when(taskParticipationService.lockFactsOf(TASK_ID)).thenReturn(publishedTask(null, 1));
        when(applicationMapper.selectById(APPLICATION_ID)).thenReturn(pendingApplication(EMPLOYER_ID));

        ApiException ex = catchThrowableOfType(
                () -> hireService.hire(EMPLOYER, TASK_ID, APPLICATION_ID), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SELF_HIRE_FORBIDDEN);
        verify(applicationMapper, never()).update(any(), any());
        verifyNoInteractions(taskSlotOccupancyService);
    }

    /** 当前身份不是雇主：403，且不读任务。 */
    @Test
    void hireShouldRejectNonEmployer() {
        ApiException ex = catchThrowableOfType(
                () -> hireService.hire(WORKER, TASK_ID, APPLICATION_ID), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(taskParticipationService, applicationMapper, taskSlotOccupancyService);
    }

    /** 任务已撤单：409，且不读报名。 */
    @Test
    void hireShouldRejectCancelledTask() {
        when(taskParticipationService.lockFactsOf(TASK_ID))
                .thenReturn(publishedTask(LocalDateTime.now().minusMinutes(5), 1));

        ApiException ex = catchThrowableOfType(
                () -> hireService.hire(EMPLOYER, TASK_ID, APPLICATION_ID), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        verifyNoInteractions(applicationMapper, taskSlotOccupancyService);
    }

    /** 卡片 §2.3.8：同一报名重复录用 → 返回同一签约 ID，不新建第二份签约。 */
    @Test
    void hireShouldBeIdempotentForHiredApplication() {
        EngagementApplication hired = pendingApplication(WORKER_ID);
        hired.setStatus(ApplicationStatus.APPLY_HIRED);
        EngagementContract existing = new EngagementContract();
        existing.setId(CONTRACT_ID);

        when(taskParticipationService.lockFactsOf(TASK_ID)).thenReturn(publishedTask(null, 1));
        when(applicationMapper.selectById(APPLICATION_ID)).thenReturn(hired);
        when(contractMapper.selectOne(any())).thenReturn(existing);

        assertThat(hireService.hire(EMPLOYER, TASK_ID, APPLICATION_ID)).isEqualTo(CONTRACT_ID);
        verify(applicationMapper, never()).update(any(), any());
        verifyNoInteractions(taskSlotOccupancyService);
    }

    /** FR-SIGN.HIRE.03：名额已被抢光 → SLOT_TAKEN，不建签约。 */
    @Test
    void hireShouldPropagateSlotTakenWithoutContract() {
        when(taskParticipationService.lockFactsOf(TASK_ID)).thenReturn(publishedTask(null, 1));
        when(applicationMapper.selectById(APPLICATION_ID)).thenReturn(pendingApplication(WORKER_ID));
        when(applicationMapper.update(any(), any())).thenReturn(1);
        when(taskSlotOccupancyService.occupyForHire(TASK_ID, APPLICATION_ID))
                .thenThrow(new ApiException(ErrorCode.SLOT_TAKEN, "名额已被占用"));

        ApiException ex = catchThrowableOfType(
                () -> hireService.hire(EMPLOYER, TASK_ID, APPLICATION_ID), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SLOT_TAKEN);
        verify(contractMapper, never()).insert(any(EngagementContract.class));
    }

    private static ParticipationFacts publishedTask(LocalDateTime cancelledAt, long availableSlots) {
        return new ParticipationFacts(TASK_ID, EMPLOYER_ID, LocalDateTime.now().minusDays(1),
                cancelledAt, LocalDateTime.now().plusDays(1), availableSlots);
    }

    private static EngagementApplication pendingApplication(long workerId) {
        EngagementApplication application = new EngagementApplication();
        application.setId(APPLICATION_ID);
        application.setTaskId(TASK_ID);
        application.setWorkerId(workerId);
        application.setStatus(ApplicationStatus.APPLY_PENDING);
        return application;
    }
}
