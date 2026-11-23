package com.escrow.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.engagement.api.ApplicationListItemView;
import com.escrow.platform.engagement.application.ApplicationService;
import com.escrow.platform.engagement.domain.ApplicationPageQuery;
import com.escrow.platform.engagement.domain.ApplicationStatus;
import com.escrow.platform.engagement.domain.EngagementApplication;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementApplicationMapper;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 报名的真库并发与数据权限测试（v3-c1，FR-SIGN.APPLY.03/.04/.07）。
 *
 * <p>只在 {@code ESCROW_IT=true} 且 MySQL 的 {@code escrow_test} 可用时运行；
 * 默认 {@code mvn test} 不连库，保持「MySQL 未启动也必须绿」。</p>
 *
 * <p>并发用例必须打真库：假 Mapper 无法证明「同一劳动者只可能一笔成功」——
 * 这是生成列唯一索引的行锁语义，只有真实 InnoDB 才能验证。</p>
 *
 * @since V3
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V3ApplicationConcurrencyIntegrationTest {

    private static final long OWNER_ID = 1001L;
    private static final LoginUser OWNER =
            new LoginUser(OWNER_ID, "owner@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final LoginUser OTHER_EMPLOYER =
            new LoginUser(1002L, "other@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final LoginUser WORKER =
            new LoginUser(2001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private EngagementApplicationMapper applicationMapper;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private TaskSlotMapper taskSlotMapper;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        applicationMapper.delete(null);
        taskSlotMapper.delete(null);
        taskMapper.delete(null);
    }

    /** FR-SIGN.APPLY.03：同一劳动者并发报名 8 次 → 恰好 1 笔成功，其余 ALREADY_APPLIED。 */
    @Test
    void concurrentDuplicateApplicationShouldCreateExactlyOneRecord() throws Exception {
        Long taskId = insertPublishedTask(1);

        List<ErrorCode> results = applyConcurrently(taskId, Collections.nCopies(8, WORKER));

        assertThat(results).filteredOn(Objects::isNull).hasSize(1);
        assertThat(results).filteredOn(Objects::nonNull).containsOnly(ErrorCode.ALREADY_APPLIED);
        assertThat(countApplications(taskId)).isEqualTo(1);
    }

    /** FR-SIGN.APPLY.04：1 个名额的任务 20 人报名全部成功，名额仍是待占用。 */
    @Test
    void applicationsShouldNotOccupySlots() throws Exception {
        Long taskId = insertPublishedTask(1);
        List<LoginUser> workers = IntStream.range(0, 20)
                .mapToObj(i -> new LoginUser(3000L + i, "worker" + i + "@example.com",
                        UserRole.WORKER, IdentityType.WORKER))
                .toList();

        List<ErrorCode> results = applyConcurrently(taskId, workers);

        assertThat(results).containsOnlyNulls();
        assertThat(countApplications(taskId)).isEqualTo(20);
        TaskSlot slot = taskSlotMapper.selectOne(new LambdaQueryWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, taskId));
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.SLOT_AVAILABLE);
        assertThat(slot.getApplicationId()).isNull();
    }

    /** FR-SIGN.APPLY.07：仅本任务雇主可读；其他雇主与劳动者均 403。 */
    @Test
    void applicantListShouldBeVisibleOnlyToOwningEmployer() {
        Long taskId = insertPublishedTask(2);
        applicationService.apply(WORKER, taskId);
        applicationService.apply(new LoginUser(2002L, "worker2@example.com",
                UserRole.WORKER, IdentityType.WORKER), taskId);

        PageView<ApplicationListItemView> view =
                applicationService.list(OWNER, taskId, ApplicationPageQuery.from(null, null));

        assertThat(view.total()).isEqualTo(2);
        assertThat(view.items()).extracting(ApplicationListItemView::workerId)
                .containsExactlyInAnyOrder(2001L, 2002L);
        assertThat(view.items()).extracting(ApplicationListItemView::status)
                .containsOnly(ApplicationStatus.APPLY_PENDING);

        ApiException otherEmployer = catchThrowableOfType(
                () -> applicationService.list(OTHER_EMPLOYER, taskId, ApplicationPageQuery.from(null, null)),
                ApiException.class);
        assertThat(otherEmployer.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);

        ApiException worker = catchThrowableOfType(
                () -> applicationService.list(WORKER, taskId, ApplicationPageQuery.from(null, null)),
                ApiException.class);
        assertThat(worker.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    private Long insertPublishedTask(int slotCount) {
        Task task = new Task();
        task.setId(IdWorker.getId());
        task.setEmployerId(OWNER_ID);
        task.setTitle("并发报名测试任务");
        task.setDescription("用于验证报名不占名额与唯一索引");
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

    private List<ErrorCode> applyConcurrently(Long taskId, List<LoginUser> users) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(users.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ErrorCode>> futures = users.stream()
                    .map(user -> pool.submit(() -> {
                        start.await();
                        try {
                            applicationService.apply(user, taskId);
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

    private long countApplications(Long taskId) {
        return applicationMapper.selectCount(new LambdaQueryWrapper<EngagementApplication>()
                .eq(EngagementApplication::getTaskId, taskId));
    }
}
