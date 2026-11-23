package com.escrow.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.engagement.application.HireService;
import com.escrow.platform.engagement.domain.ApplicationStatus;
import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.engagement.domain.EngagementApplication;
import com.escrow.platform.engagement.domain.EngagementContract;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementApplicationMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementContractMapper;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.application.TaskCancelService;
import com.escrow.platform.task.application.TaskExpiryService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 录用与 APPLY.08 收口的真库测试（v3-c2，FR-SIGN.HIRE.01~.04 / FR-SIGN.APPLY.08）。
 *
 * <p>只在 {@code ESCROW_IT=true} 且 MySQL 的 {@code escrow_test} 可用时运行；
 * 默认 {@code mvn test} 不连库，保持「MySQL 未启动也必须绿」。</p>
 *
 * <p>并发用例必须打真库：名额占用靠 InnoDB 行锁 + 受影响行数判定，
 * 假 Mapper 无法证明「一个名额只可能被一笔录用抢到」。</p>
 *
 * @since V3
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V3HireConcurrencyIntegrationTest {

    private static final long OWNER_ID = 1001L;
    private static final LoginUser OWNER =
            new LoginUser(OWNER_ID, "owner@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final LoginUser WORKER_1 =
            new LoginUser(2001L, "worker1@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final LoginUser WORKER_2 =
            new LoginUser(2002L, "worker2@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Autowired
    private HireService hireService;
    @Autowired
    private TaskCancelService taskCancelService;
    @Autowired
    private TaskExpiryService taskExpiryService;
    @Autowired
    private EngagementApplicationMapper applicationMapper;
    @Autowired
    private EngagementContractMapper contractMapper;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private TaskSlotMapper taskSlotMapper;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        contractMapper.delete(null);
        applicationMapper.delete(null);
        taskSlotMapper.delete(null);
        taskMapper.delete(null);
    }

    /** FR-SIGN.HIRE.03：1 个名额并发录用两人 → 恰好 1 笔成功，另 1 笔 SLOT_TAKEN。 */
    @Test
    void concurrentHireShouldSucceedExactlyOnce() throws Exception {
        Long taskId = insertPublishedTask(1);
        Long applicationA = insertPendingApplication(taskId, WORKER_1.userId());
        Long applicationB = insertPendingApplication(taskId, WORKER_2.userId());

        List<ErrorCode> results = hireConcurrently(taskId, List.of(applicationA, applicationB));

        assertThat(results).filteredOn(Objects::isNull).hasSize(1);
        assertThat(results).filteredOn(Objects::nonNull).containsOnly(ErrorCode.SLOT_TAKEN);

        List<EngagementContract> contracts = contractMapper.selectList(
                new LambdaQueryWrapper<EngagementContract>().eq(EngagementContract::getTaskId, taskId));
        assertThat(contracts).hasSize(1);
        EngagementContract contract = contracts.get(0);
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.CONTRACT_ACTIVE);

        // 落败者被满员收口拒绝，胜利者已录用
        assertThat(applicationMapper.selectById(contract.getApplicationId()).getStatus())
                .isEqualTo(ApplicationStatus.APPLY_HIRED);
        Long loserId = contract.getApplicationId().equals(applicationA) ? applicationB : applicationA;
        assertThat(applicationMapper.selectById(loserId).getStatus())
                .isEqualTo(ApplicationStatus.APPLY_REJECTED);

        // 名额回填了报名与签约，满员不改 recruiting_closed（FR-SIGN.HIRE.02）
        TaskSlot slot = onlySlotOf(taskId);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.SLOT_OCCUPIED);
        assertThat(slot.getApplicationId()).isEqualTo(contract.getApplicationId());
        assertThat(slot.getEngagementId()).isEqualTo(contract.getId());
        assertThat(taskMapper.selectById(taskId).getRecruitingClosed()).isFalse();
    }

    /** 卡片 §2.3.8：同一报名重复录用 → 同一签约 ID，不新建第二份签约。 */
    @Test
    void repeatedHireShouldReturnSameContract() {
        Long taskId = insertPublishedTask(2);
        Long applicationId = insertPendingApplication(taskId, WORKER_1.userId());

        Long first = hireService.hire(OWNER, taskId, applicationId);
        Long second = hireService.hire(OWNER, taskId, applicationId);

        assertThat(second).isEqualTo(first);
        assertThat(contractMapper.selectCount(new LambdaQueryWrapper<EngagementContract>()
                .eq(EngagementContract::getApplicationId, applicationId))).isEqualTo(1);
        // 第二个名额没被顺手占掉
        assertThat(taskSlotMapper.selectCount(new LambdaQueryWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getStatus, SlotStatus.SLOT_AVAILABLE))).isEqualTo(1);
    }

    /** FR-SIGN.APPLY.08（满员）：占完最后一个名额 → 其余待处理报名全部拒绝。 */
    @Test
    void fullHouseShouldRejectAllPendingApplications() {
        Long taskId = insertPublishedTask(1);
        Long hiredApplication = insertPendingApplication(taskId, WORKER_1.userId());
        Long pendingApplication = insertPendingApplication(taskId, WORKER_2.userId());

        Long contractId = hireService.hire(OWNER, taskId, hiredApplication);

        assertThat(contractId).isNotNull();
        assertThat(applicationMapper.selectById(hiredApplication).getStatus())
                .isEqualTo(ApplicationStatus.APPLY_HIRED);
        assertThat(applicationMapper.selectById(pendingApplication).getStatus())
                .isEqualTo(ApplicationStatus.APPLY_REJECTED);
        assertThat(taskMapper.selectById(taskId).getRecruitingClosed()).isFalse();
    }

    /** FR-SIGN.APPLY.08（撤单）：撤单成功后该任务待处理报名全部拒绝。 */
    @Test
    void cancelShouldRejectAllPendingApplications() {
        Long taskId = insertPublishedTask(2);
        Long applicationA = insertPendingApplication(taskId, WORKER_1.userId());
        Long applicationB = insertPendingApplication(taskId, WORKER_2.userId());

        taskCancelService.cancel(OWNER, taskId);

        assertThat(applicationMapper.selectById(applicationA).getStatus())
                .isEqualTo(ApplicationStatus.APPLY_REJECTED);
        assertThat(applicationMapper.selectById(applicationB).getStatus())
                .isEqualTo(ApplicationStatus.APPLY_REJECTED);
        assertThat(taskMapper.selectById(taskId).getCancelledAt()).isNotNull();
    }

    /** FR-SIGN.APPLY.08（到期）：到期收口后待处理报名全部拒绝，已生效签约不动。 */
    @Test
    void expiryShouldRejectAllPendingApplications() {
        Long taskId = insertPublishedTask(2);
        Long hiredApplication = insertPendingApplication(taskId, WORKER_1.userId());
        Long pendingApplication = insertPendingApplication(taskId, WORKER_2.userId());
        Long contractId = hireService.hire(OWNER, taskId, hiredApplication);

        // 到期判定直接看 end_time；整体平移时间窗（ck_task_time_range 要求 end_time > start_time）
        taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, taskId)
                .set(Task::getStartTime, LocalDateTime.now().minusHours(3))
                .set(Task::getEndTime, LocalDateTime.now().minusMinutes(1)));

        assertThat(taskExpiryService.expireTask(taskId)).isTrue();

        assertThat(applicationMapper.selectById(pendingApplication).getStatus())
                .isEqualTo(ApplicationStatus.APPLY_REJECTED);
        assertThat(applicationMapper.selectById(hiredApplication).getStatus())
                .isEqualTo(ApplicationStatus.APPLY_HIRED);
        assertThat(contractMapper.selectById(contractId).getStatus())
                .isEqualTo(ContractStatus.CONTRACT_ACTIVE);
        assertThat(taskMapper.selectById(taskId).getRecruitingClosed()).isTrue();
    }

    private Long insertPublishedTask(int slotCount) {
        Task task = new Task();
        task.setId(IdWorker.getId());
        task.setEmployerId(OWNER_ID);
        task.setTitle("录用并发测试任务");
        task.setDescription("用于验证名额占用与满员收口");
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

    private List<ErrorCode> hireConcurrently(Long taskId, List<Long> applicationIds) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(applicationIds.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ErrorCode>> futures = applicationIds.stream()
                    .map(applicationId -> pool.submit(() -> {
                        start.await();
                        try {
                            hireService.hire(OWNER, taskId, applicationId);
                            return null;
                        } catch (ApiException ex) {
                            return ex.getErrorCode();
                        }
                    }))
                    .toList();
            start.countDown();

            List<ErrorCode> results = new ArrayList<>(futures.size());
            for (Future<ErrorCode> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private TaskSlot onlySlotOf(Long taskId) {
        return taskSlotMapper.selectOne(new LambdaQueryWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, taskId));
    }
}
