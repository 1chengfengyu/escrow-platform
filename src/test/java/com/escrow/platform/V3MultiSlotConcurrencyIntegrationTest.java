package com.escrow.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.escrow.platform.task.api.TaskDetailView;
import com.escrow.platform.task.application.TaskQueryService;
import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.domain.TaskDisplayStatus;
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
 * 多名额并发录用不超卖的真库测试（v3-c5，FR-SIGN.HIRE.03 / docs/03 V3 演示 2）。
 *
 * <p>20 个名额、30 份待处理报名同时录用：恰好 20 笔成功、10 笔 {@code SLOT_TAKEN}，
 * 名额集合里恰好 20 个「已占用」且无「待占用」。录用按任务行排他锁串行化，
 * 但正确性仍依赖「条件更新 + 受影响行数」，假 Mapper 证明不了。</p>
 *
 * <p>只在 {@code ESCROW_IT=true} 且 {@code escrow_test} 可用时运行。</p>
 *
 * @since V3
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V3MultiSlotConcurrencyIntegrationTest {

    private static final int SLOT_COUNT = 20;
    private static final int APPLICATION_COUNT = 30;
    private static final long OWNER_ID = 1001L;
    private static final LoginUser OWNER =
            new LoginUser(OWNER_ID, "owner@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final LoginUser VIEWER =
            new LoginUser(9001L, "viewer@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Autowired
    private HireService hireService;
    @Autowired
    private TaskQueryService taskQueryService;
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

    /** FR-SIGN.HIRE.03：30 人抢 20 名额 → 恰好 20 成功，无超卖。 */
    @Test
    void concurrentHireShouldNeverOversellManySlots() throws Exception {
        Long taskId = insertPublishedTask(SLOT_COUNT);
        List<Long> applicationIds = new ArrayList<>(APPLICATION_COUNT);
        for (int i = 0; i < APPLICATION_COUNT; i++) {
            applicationIds.add(insertPendingApplication(taskId, 3001L + i));
        }

        List<ErrorCode> results = hireConcurrently(taskId, applicationIds);

        assertThat(results).filteredOn(Objects::isNull).hasSize(SLOT_COUNT);
        assertThat(results).filteredOn(Objects::nonNull)
                .hasSize(APPLICATION_COUNT - SLOT_COUNT)
                .containsOnly(ErrorCode.SLOT_TAKEN);

        List<EngagementContract> contracts = contractMapper.selectList(
                new LambdaQueryWrapper<EngagementContract>().eq(EngagementContract::getTaskId, taskId));
        assertThat(contracts).hasSize(SLOT_COUNT);
        assertThat(contracts).extracting(EngagementContract::getStatus)
                .containsOnly(ContractStatus.CONTRACT_ACTIVE);
        assertThat(contracts).extracting(EngagementContract::getSlotId).doesNotHaveDuplicates();

        assertThat(taskSlotMapper.selectCount(new LambdaQueryWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getStatus, SlotStatus.SLOT_OCCUPIED))).isEqualTo((long) SLOT_COUNT);
        assertThat(taskSlotMapper.selectCount(new LambdaQueryWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getStatus, SlotStatus.SLOT_AVAILABLE))).isZero();

        assertThat(applicationMapper.selectCount(new LambdaQueryWrapper<EngagementApplication>()
                .eq(EngagementApplication::getTaskId, taskId)
                .eq(EngagementApplication::getStatus, ApplicationStatus.APPLY_HIRED)))
                .isEqualTo((long) SLOT_COUNT);
        assertThat(applicationMapper.selectCount(new LambdaQueryWrapper<EngagementApplication>()
                .eq(EngagementApplication::getTaskId, taskId)
                .eq(EngagementApplication::getStatus, ApplicationStatus.APPLY_REJECTED)))
                .isEqualTo((long) (APPLICATION_COUNT - SLOT_COUNT));

        // 名额已满但未撤单未到期：派生状态「已接单」，招募开关仍为 false（FR-SIGN.HIRE.02）
        TaskDetailView detail = taskQueryService.detail(VIEWER, taskId);
        assertThat(detail.status()).isEqualTo(TaskDisplayStatus.TASK_TAKEN);
        assertThat(detail.recruitingClosed()).isFalse();
    }

    private Long insertPublishedTask(int slotCount) {
        Task task = new Task();
        task.setId(IdWorker.getId());
        task.setEmployerId(OWNER_ID);
        task.setTitle("多名额并发录用测试");
        task.setDescription("验证 20 名额 × 30 并发不超卖");
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
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
