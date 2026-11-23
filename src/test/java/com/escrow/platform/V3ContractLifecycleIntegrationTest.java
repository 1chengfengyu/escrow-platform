package com.escrow.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.engagement.application.ContractService;
import com.escrow.platform.engagement.application.HireService;
import com.escrow.platform.engagement.domain.ApplicationStatus;
import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.engagement.domain.EngagementApplication;
import com.escrow.platform.engagement.domain.EngagementContract;
import com.escrow.platform.engagement.domain.EngagementPerformanceRecord;
import com.escrow.platform.engagement.domain.PerformanceEventType;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementApplicationMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementContractMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementPerformanceRecordMapper;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.domain.TaskSlot;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
import com.escrow.platform.task.infrastructure.mapper.TaskSlotMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 开始履约 / 取消签约 / 超时自动取消的真库测试（v3-c3，FR-SIGN.START.01~.02 / FR-SIGN.CANCEL.01~.04）。
 *
 * <p>只在 {@code ESCROW_IT=true} 且 MySQL 的 {@code escrow_test} 可用时运行。
 * 必须打真库：三写一致性依赖条件更新与生成列唯一索引，假 Mapper 证明不了
 * 「取消后名额真的能再次被录用」。</p>
 *
 * @since V3
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V3ContractLifecycleIntegrationTest {

    private static final long OWNER_ID = 1001L;
    private static final LoginUser OWNER =
            new LoginUser(OWNER_ID, "owner@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final LoginUser WORKER =
            new LoginUser(2001L, "worker1@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Autowired
    private HireService hireService;
    @Autowired
    private ContractService contractService;
    @Autowired
    private EngagementApplicationMapper applicationMapper;
    @Autowired
    private EngagementContractMapper contractMapper;
    @Autowired
    private EngagementPerformanceRecordMapper performanceRecordMapper;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private TaskSlotMapper taskSlotMapper;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        performanceRecordMapper.delete(null);
        contractMapper.delete(null);
        applicationMapper.delete(null);
        taskSlotMapper.delete(null);
        taskMapper.delete(null);
    }

    /** FR-SIGN.START.01：签约 → 履约中，名额 → 履约中，并记一条开始履约记录。 */
    @Test
    void startShouldMoveContractAndSlotToInProgress() {
        Long taskId = insertPublishedTask(1);
        Long contractId = hireFirstSlot(taskId);

        ContractStatus status = contractService.start(WORKER, contractId);

        assertThat(status).isEqualTo(ContractStatus.CONTRACT_STARTED);
        EngagementContract contract = contractMapper.selectById(contractId);
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.CONTRACT_STARTED);
        assertThat(contract.getStartedAt()).isNotNull();

        TaskSlot slot = onlySlotOf(taskId);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.SLOT_IN_PROGRESS);
        assertThat(slot.getEngagementId()).isEqualTo(contractId);
        assertThat(applicationMapper.selectById(contract.getApplicationId()).getStatus())
                .isEqualTo(ApplicationStatus.APPLY_HIRED);

        assertThat(recordsOf(contractId)).extracting(EngagementPerformanceRecord::getEventType)
                .containsExactly(PerformanceEventType.PERFORMANCE_START);
    }

    /** FR-SIGN.CANCEL.01 / CANCEL.04：取消后名额回「待占用」（非「已释放」），报名撤回，托管不退款。 */
    @Test
    void cancelShouldReopenSlotAndWithdrawApplication() {
        Long taskId = insertPublishedTask(1);
        Long contractId = hireFirstSlot(taskId);
        Long applicationId = contractMapper.selectById(contractId).getApplicationId();

        ContractStatus status = contractService.cancel(WORKER, contractId);

        assertThat(status).isEqualTo(ContractStatus.CONTRACT_CANCELLED);
        assertThat(contractMapper.selectById(contractId).getStatus())
                .isEqualTo(ContractStatus.CONTRACT_CANCELLED);
        assertThat(applicationMapper.selectById(applicationId).getStatus())
                .isEqualTo(ApplicationStatus.APPLY_WITHDRAWN);

        TaskSlot slot = onlySlotOf(taskId);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.SLOT_AVAILABLE);
        assertThat(slot.getApplicationId()).isNull();
        assertThat(slot.getEngagementId()).isNull();

        assertThat(recordsOf(contractId)).extracting(EngagementPerformanceRecord::getEventType)
                .containsExactly(PerformanceEventType.PERFORMANCE_CANCEL);
    }

    /** FR-SIGN.CANCEL.02：取消后同一名额可被再次录用，旧签约不阻碍新签约。 */
    @Test
    void cancelledSlotShouldBeHireableAgain() {
        Long taskId = insertPublishedTask(1);
        Long firstContractId = hireFirstSlot(taskId);

        contractService.cancel(WORKER, firstContractId);

        Long secondApplicationId = insertPendingApplication(taskId, WORKER.userId());
        Long secondContractId = hireService.hire(OWNER, taskId, secondApplicationId);

        assertThat(secondContractId).isNotEqualTo(firstContractId);
        EngagementContract second = contractMapper.selectById(secondContractId);
        assertThat(second.getStatus()).isEqualTo(ContractStatus.CONTRACT_ACTIVE);

        TaskSlot slot = onlySlotOf(taskId);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.SLOT_OCCUPIED);
        assertThat(slot.getApplicationId()).isEqualTo(secondApplicationId);
        assertThat(slot.getEngagementId()).isEqualTo(secondContractId);
    }

    /** FR-SIGN.CANCEL.03：已开始履约不能单方取消，名额与签约保持不动。 */
    @Test
    void cancelShouldBeRejectedAfterStart() {
        Long taskId = insertPublishedTask(1);
        Long contractId = hireFirstSlot(taskId);
        contractService.start(WORKER, contractId);

        ApiException ex = catchThrowableOfType(
                () -> contractService.cancel(WORKER, contractId), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(contractMapper.selectById(contractId).getStatus())
                .isEqualTo(ContractStatus.CONTRACT_STARTED);
        assertThat(onlySlotOf(taskId).getStatus()).isEqualTo(SlotStatus.SLOT_IN_PROGRESS);
    }

    /** FR-SIGN.START.02 / FR-JOB.START.01：超过挂起时长未开始 → 自动取消并记「超时取消」。 */
    @Test
    void autoCancelShouldReopenSlotAndRecordTimeout() {
        Long taskId = insertPublishedTask(1);
        Long contractId = hireFirstSlot(taskId);
        Long applicationId = contractMapper.selectById(contractId).getApplicationId();

        // 把生效时间推到 25 小时前（默认挂起时长 24 小时）
        contractMapper.update(null, new LambdaUpdateWrapper<EngagementContract>()
                .eq(EngagementContract::getId, contractId)
                .set(EngagementContract::getEffectiveAt, LocalDateTime.now().minusHours(25)));

        assertThat(contractService.findOverdueContractIds()).contains(contractId);
        assertThat(contractService.autoCancel(contractId)).isTrue();

        assertThat(contractMapper.selectById(contractId).getStatus())
                .isEqualTo(ContractStatus.CONTRACT_CANCELLED);
        assertThat(applicationMapper.selectById(applicationId).getStatus())
                .isEqualTo(ApplicationStatus.APPLY_WITHDRAWN);
        TaskSlot slot = onlySlotOf(taskId);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.SLOT_AVAILABLE);
        assertThat(slot.getEngagementId()).isNull();
        assertThat(recordsOf(contractId)).extracting(EngagementPerformanceRecord::getEventType)
                .containsExactly(PerformanceEventType.PERFORMANCE_TIMEOUT_CANCEL);

        // 已取消的签约不会被二次处理
        assertThat(contractService.autoCancel(contractId)).isFalse();
    }

    private Long hireFirstSlot(Long taskId) {
        Long applicationId = insertPendingApplication(taskId, WORKER.userId());
        return hireService.hire(OWNER, taskId, applicationId);
    }

    private java.util.List<EngagementPerformanceRecord> recordsOf(Long contractId) {
        return performanceRecordMapper.selectList(new LambdaQueryWrapper<EngagementPerformanceRecord>()
                .eq(EngagementPerformanceRecord::getContractId, contractId));
    }

    private Long insertPublishedTask(int slotCount) {
        Task task = new Task();
        task.setId(IdWorker.getId());
        task.setEmployerId(OWNER_ID);
        task.setTitle("签约履约测试任务");
        task.setDescription("用于验证开始履约与取消签约");
        task.setCategory("测试");
        task.setUnitPrice(1_000L);
        task.setSlotCount(slotCount);
        task.setStartTime(LocalDateTime.now().plusHours(1));
        task.setEndTime(LocalDateTime.now().plusDays(1));
        task.setWorkLocation("测试地点");
        task.setPublishedAt(LocalDateTime.now().minusHours(1));
        task.setRecruitingClosed(false);
        taskMapper.insert(task);

        for (int seqNo = 1; seqNo <= slotCount; seqNo++) {
            TaskSlot slot = new TaskSlot();
            slot.setId(IdWorker.getId());
            slot.setTaskId(task.getId());
            slot.setSeqNo(seqNo);
            slot.setStatus(SlotStatus.SLOT_AVAILABLE);
            taskSlotMapper.insert(slot);
        }
        return task.getId();
    }

    private Long insertPendingApplication(Long taskId, Long workerId) {
        EngagementApplication application = new EngagementApplication();
        application.setId(IdWorker.getId());
        application.setTaskId(taskId);
        application.setWorkerId(workerId);
        application.setStatus(ApplicationStatus.APPLY_PENDING);
        applicationMapper.insert(application);
        return application.getId();
    }

    private TaskSlot onlySlotOf(Long taskId) {
        return taskSlotMapper.selectOne(new LambdaQueryWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, taskId));
    }
}
