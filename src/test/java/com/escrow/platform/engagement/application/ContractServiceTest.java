package com.escrow.platform.engagement.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.engagement.domain.DeliverableSubmission;
import com.escrow.platform.engagement.domain.EngagementApplication;
import com.escrow.platform.engagement.domain.EngagementContract;
import com.escrow.platform.engagement.domain.EngagementDeliverable;
import com.escrow.platform.engagement.domain.PerformanceEventType;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementApplicationMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementContractMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementDeliverableMapper;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.application.TaskSlotOccupancyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 签约履约服务单元测试（v3-c3，FR-SIGN.START.01~.02 / FR-SIGN.CANCEL.01~.03）。
 *
 * <p>只锁守卫顺序、错误码与「签约 → 名额 → 报名 → 履约记录」的调用编排；
 * 三表真一致性由真库用例 {@code V3ContractLifecycleIntegrationTest} 证明。</p>
 *
 * @since V3
 */
@ExtendWith(MockitoExtension.class)
class ContractServiceTest {

    private static final long TASK_ID = 10L;
    private static final long SLOT_ID = 21L;
    private static final long CONTRACT_ID = 5001L;
    private static final long APPLICATION_ID = 3001L;
    private static final long WORKER_ID = 2001L;

    private static final LoginUser WORKER =
            new LoginUser(WORKER_ID, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final LoginUser EMPLOYER =
            new LoginUser(1001L, "employer@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);

    @Mock
    private EngagementContractMapper contractMapper;

    @Mock
    private EngagementApplicationMapper applicationMapper;

    @Mock
    private EngagementDeliverableMapper deliverableMapper;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private TaskSlotOccupancyService taskSlotOccupancyService;

    @Mock
    private PerformanceRecordService performanceRecordService;

    @InjectMocks
    private ContractService contractService;

    /** 条件更新用 Lambda 构造器，纯单测需先初始化 {@code TableInfo} 缓存。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, EngagementContract.class);
        TableInfoHelper.initTableInfo(assistant, EngagementApplication.class);
    }

    /** FR-SIGN.START.01：签约转履约中 → 名额转履约中 → 记开始履约记录。 */
    @Test
    void startShouldAdvanceContractSlotAndRecord() {
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(activeContract());
        when(contractMapper.update(any(), any())).thenReturn(1);

        ContractStatus status = contractService.start(WORKER, CONTRACT_ID);

        assertThat(status).isEqualTo(ContractStatus.CONTRACT_STARTED);
        verify(taskSlotOccupancyService).startPerformance(TASK_ID, SLOT_ID);
        verify(performanceRecordService).record(org.mockito.ArgumentMatchers.eq(WORKER_ID),
                org.mockito.ArgumentMatchers.eq(CONTRACT_ID),
                org.mockito.ArgumentMatchers.eq(PerformanceEventType.PERFORMANCE_START), any());
    }

    /** 已开始履约：不能重复开始（409），不写名额、不记记录。 */
    @Test
    void startShouldRejectNonActiveContract() {
        EngagementContract started = activeContract();
        started.setStatus(ContractStatus.CONTRACT_STARTED);
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(started);

        ApiException ex = catchThrowableOfType(
                () -> contractService.start(WORKER, CONTRACT_ID), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        verify(contractMapper, never()).update(any(), any());
        verifyNoInteractions(taskSlotOccupancyService, performanceRecordService);
    }

    /** 非本签约劳动者：403，不写任何东西。 */
    @Test
    void startShouldRejectOtherWorker() {
        EngagementContract other = activeContract();
        other.setWorkerId(9999L);
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(other);

        ApiException ex = catchThrowableOfType(
                () -> contractService.start(WORKER, CONTRACT_ID), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(taskSlotOccupancyService, performanceRecordService);
    }

    /** 非劳动者身份：403，连签约都不读。 */
    @Test
    void startShouldRejectNonWorkerIdentity() {
        ApiException ex = catchThrowableOfType(
                () -> contractService.start(EMPLOYER, CONTRACT_ID), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(contractMapper, taskSlotOccupancyService, performanceRecordService);
    }

    /** 签约不存在：404。 */
    @Test
    void startShouldRejectMissingContract() {
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(null);

        ApiException ex = catchThrowableOfType(
                () -> contractService.start(WORKER, CONTRACT_ID), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** FR-SIGN.CANCEL.01：签约取消 → 名额回待占用 → 报名撤回 → 记取消记录。 */
    @Test
    void cancelShouldReopenSlotWithdrawApplicationAndRecord() {
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(activeContract());
        when(contractMapper.update(any(), any())).thenReturn(1);
        when(applicationMapper.update(any(), any())).thenReturn(1);

        ContractStatus status = contractService.cancel(WORKER, CONTRACT_ID);

        assertThat(status).isEqualTo(ContractStatus.CONTRACT_CANCELLED);
        verify(taskSlotOccupancyService).reopenAfterCancel(TASK_ID, SLOT_ID);
        verify(applicationMapper).update(any(), any());
        verify(performanceRecordService).record(org.mockito.ArgumentMatchers.eq(WORKER_ID),
                org.mockito.ArgumentMatchers.eq(CONTRACT_ID),
                org.mockito.ArgumentMatchers.eq(PerformanceEventType.PERFORMANCE_CANCEL), any());
    }

    /** FR-SIGN.CANCEL.03：已开始履约不得单方取消（409），不碰名额与报名。 */
    @Test
    void cancelShouldRejectStartedContract() {
        EngagementContract started = activeContract();
        started.setStatus(ContractStatus.CONTRACT_STARTED);
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(started);

        ApiException ex = catchThrowableOfType(
                () -> contractService.cancel(WORKER, CONTRACT_ID), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        verifyNoInteractions(taskSlotOccupancyService, applicationMapper, performanceRecordService);
    }

    /** 并发开始抢在前面：签约条件更新 0 行 → 409，且不做后续写。 */
    @Test
    void cancelShouldConflictWhenClaimLoses() {
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(activeContract());
        when(contractMapper.update(any(), any())).thenReturn(0);

        ApiException ex = catchThrowableOfType(
                () -> contractService.cancel(WORKER, CONTRACT_ID), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        verifyNoInteractions(taskSlotOccupancyService, applicationMapper, performanceRecordService);
    }

    /** FR-SIGN.START.02：超过挂起时长仍未开始 → 自动取消并记「超时取消」事件。 */
    @Test
    void autoCancelShouldCancelOverdueContract() {
        ReflectionTestUtils.setField(contractService, "pendingStartHours", 24L);
        EngagementContract overdue = activeContract();
        overdue.setEffectiveAt(LocalDateTime.now().minusHours(25));
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(overdue);
        when(contractMapper.update(any(), any())).thenReturn(1);
        when(applicationMapper.update(any(), any())).thenReturn(1);

        assertThat(contractService.autoCancel(CONTRACT_ID)).isTrue();

        verify(taskSlotOccupancyService).reopenAfterCancel(TASK_ID, SLOT_ID);
        verify(performanceRecordService).record(org.mockito.ArgumentMatchers.eq(WORKER_ID),
                org.mockito.ArgumentMatchers.eq(CONTRACT_ID),
                org.mockito.ArgumentMatchers.eq(PerformanceEventType.PERFORMANCE_TIMEOUT_CANCEL), any());
    }

    /** 未到挂起时长：不取消，返回 false。 */
    @Test
    void autoCancelShouldSkipFreshContract() {
        ReflectionTestUtils.setField(contractService, "pendingStartHours", 24L);
        EngagementContract fresh = activeContract();
        fresh.setEffectiveAt(LocalDateTime.now().minusHours(1));
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(fresh);

        assertThat(contractService.autoCancel(CONTRACT_ID)).isFalse();

        verify(contractMapper, never()).update(any(), any());
        verifyNoInteractions(taskSlotOccupancyService, applicationMapper, performanceRecordService);
    }

    /** 已取消 / 不存在的签约：扫描到也直接跳过。 */
    @Test
    void autoCancelShouldSkipCancelledOrMissingContract() {
        EngagementContract cancelled = activeContract();
        cancelled.setStatus(ContractStatus.CONTRACT_CANCELLED);
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(cancelled);
        when(contractMapper.selectById(CONTRACT_ID + 1)).thenReturn(null);

        assertThat(contractService.autoCancel(CONTRACT_ID)).isFalse();
        assertThat(contractService.autoCancel(CONTRACT_ID + 1)).isFalse();

        verify(contractMapper, never()).update(any(), any());
        verifyNoInteractions(taskSlotOccupancyService, applicationMapper, performanceRecordService);
    }

    /** 扫描：只返回生效超时的生效中签约 ID。 */
    @Test
    void findOverdueContractIdsShouldReturnIds() {
        when(contractMapper.selectList(any())).thenReturn(List.of(activeContract()));

        assertThat(contractService.findOverdueContractIds()).containsExactly(CONTRACT_ID);
    }

    /** FR-DELIVER.SUBMIT.01~.03：履约中 → 已交付 → 名额已交付 → 写交付物 → 记交付记录。 */
    @Test
    void deliverShouldAdvanceContractSlotInsertDeliverableAndRecord() throws Exception {
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(startedContract());
        when(contractMapper.update(any(), any())).thenReturn(1);
        when(objectMapper.writeValueAsString(any())).thenReturn("[\"https://img.example.com/a.jpg\"]");

        ContractStatus status = contractService.deliver(WORKER, CONTRACT_ID, submission());

        assertThat(status).isEqualTo(ContractStatus.CONTRACT_DELIVERED);
        verify(taskSlotOccupancyService).deliverForContract(TASK_ID, SLOT_ID);
        ArgumentCaptor<EngagementDeliverable> captor = ArgumentCaptor.forClass(EngagementDeliverable.class);
        verify(deliverableMapper).insert(captor.capture());
        EngagementDeliverable saved = captor.getValue();
        assertThat(saved.getContractId()).isEqualTo(CONTRACT_ID);
        assertThat(saved.getWorkerId()).isEqualTo(WORKER_ID);
        assertThat(saved.getImageUrls()).isEqualTo("[\"https://img.example.com/a.jpg\"]");
        assertThat(saved.getDescription()).isEqualTo("已完成");
        assertThat(saved.getSubmittedAt()).isNotNull();
        verify(performanceRecordService).record(org.mockito.ArgumentMatchers.eq(WORKER_ID),
                org.mockito.ArgumentMatchers.eq(CONTRACT_ID),
                org.mockito.ArgumentMatchers.eq(PerformanceEventType.PERFORMANCE_DELIVER), any());
    }

    /** 未开始履约就交付：409，不写名额 / 交付物 / 履约记录。 */
    @Test
    void deliverShouldRejectNotStartedContract() {
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(activeContract());

        ApiException ex = catchThrowableOfType(
                () -> contractService.deliver(WORKER, CONTRACT_ID, submission()), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        verify(contractMapper, never()).update(any(), any());
        verifyNoInteractions(taskSlotOccupancyService, deliverableMapper, performanceRecordService);
    }

    /** 并发下已交付：签约条件更新 0 行 → 409，不做后续写。 */
    @Test
    void deliverShouldConflictWhenClaimLoses() {
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(startedContract());
        when(contractMapper.update(any(), any())).thenReturn(0);

        ApiException ex = catchThrowableOfType(
                () -> contractService.deliver(WORKER, CONTRACT_ID, submission()), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        verifyNoInteractions(taskSlotOccupancyService, deliverableMapper, performanceRecordService);
    }

    /** 交付物唯一键冲突（状态与数据不一致）：409，且不再记履约记录。 */
    @Test
    void deliverShouldConflictWhenDeliverableAlreadyExists() throws Exception {
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(startedContract());
        when(contractMapper.update(any(), any())).thenReturn(1);
        when(objectMapper.writeValueAsString(any())).thenReturn("[\"https://img.example.com/a.jpg\"]");
        when(deliverableMapper.insert(any(EngagementDeliverable.class)))
                .thenThrow(new DuplicateKeyException("uk_engagement_deliverable_contract"));

        ApiException ex = catchThrowableOfType(
                () -> contractService.deliver(WORKER, CONTRACT_ID, submission()), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(ex.getMessage()).contains("不能重复提交");
        verify(performanceRecordService, never()).record(any(), any(), any(), any());
    }

    /** 非本签约劳动者：403，连条件更新都不做。 */
    @Test
    void deliverShouldRejectOtherWorker() {
        EngagementContract other = startedContract();
        other.setWorkerId(9999L);
        when(contractMapper.selectById(CONTRACT_ID)).thenReturn(other);

        ApiException ex = catchThrowableOfType(
                () -> contractService.deliver(WORKER, CONTRACT_ID, submission()), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(taskSlotOccupancyService, deliverableMapper, performanceRecordService);
    }

    private static DeliverableSubmission submission() {
        return DeliverableSubmission.from(List.of("https://img.example.com/a.jpg"), " 已完成 ");
    }

    private static EngagementContract startedContract() {
        EngagementContract contract = activeContract();
        contract.setStatus(ContractStatus.CONTRACT_STARTED);
        contract.setStartedAt(LocalDateTime.now().minusHours(1));
        return contract;
    }

    private static EngagementContract activeContract() {
        EngagementContract contract = new EngagementContract();
        contract.setId(CONTRACT_ID);
        contract.setTaskId(TASK_ID);
        contract.setSlotId(SLOT_ID);
        contract.setApplicationId(APPLICATION_ID);
        contract.setWorkerId(WORKER_ID);
        contract.setStatus(ContractStatus.CONTRACT_ACTIVE);
        contract.setEffectiveAt(LocalDateTime.now().minusHours(1));
        return contract;
    }
}
