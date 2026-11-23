package com.escrow.platform.task.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.fund.application.EscrowService;
import com.escrow.platform.identity.application.QualificationGate;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.domain.TaskSlot;
import com.escrow.platform.task.infrastructure.cache.TaskDetailCache;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
import com.escrow.platform.task.infrastructure.mapper.TaskSlotMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布服务测试（v2-c5）：发布即托管、重复发布幂等、抢锁失败回读、资质门禁。
 *
 * <p>事务回滚与并发「仅一笔成功」由环境门控的集成测试验证；
 * 这里验证分支与调用顺序（抢锁必须在生成名额之前）。</p>
 *
 * @since V2
 */
@ExtendWith(MockitoExtension.class)
class TaskPublishServiceTest {

    private static final long TASK_ID = 1L;
    private static final long EMPLOYER_ID = 1001L;
    private static final LoginUser EMPLOYER =
            new LoginUser(EMPLOYER_ID, "13800138000", UserRole.EMPLOYER, IdentityType.EMPLOYER);

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private TaskSlotMapper taskSlotMapper;

    @Mock
    private EscrowService escrowService;

    @Mock
    private TaskDetailCache taskDetailCache;

    @Mock
    private QualificationGate qualificationGate;

    private TaskPublishService taskPublishService;

    /** 抢锁用 {@code LambdaUpdateWrapper}，纯单测需先初始化 {@code TableInfo} 缓存。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Task.class);
        TableInfoHelper.initTableInfo(assistant, TaskSlot.class);
    }

    @BeforeEach
    void setUp() {
        taskPublishService = new TaskPublishService(taskMapper, taskSlotMapper, escrowService,
                taskDetailCache, qualificationGate, 300);
    }

    /** 发布成功：1 次抢锁 → N 条名额 → 托管冻结，最后清缓存。 */
    @Test
    void publishShouldCreateSlotsAndHoldEscrow() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(draftTask());
        when(qualificationGate.isPublishEligible(EMPLOYER_ID)).thenReturn(true);
        when(taskMapper.update(any(), any())).thenReturn(1);
        AtomicLong slotId = new AtomicLong(101L);
        when(taskSlotMapper.insert(any(TaskSlot.class))).thenAnswer(invocation -> {
            TaskSlot slot = invocation.getArgument(0);
            slot.setId(slotId.getAndIncrement());
            return 1;
        });

        taskPublishService.publish(EMPLOYER, TASK_ID);

        ArgumentCaptor<TaskSlot> slotCaptor = ArgumentCaptor.forClass(TaskSlot.class);
        verify(taskSlotMapper, times(2)).insert(slotCaptor.capture());
        assertThat(slotCaptor.getAllValues()).extracting(TaskSlot::getSeqNo).containsExactly(1, 2);
        assertThat(slotCaptor.getAllValues()).extracting(TaskSlot::getStatus)
                .containsOnly(SlotStatus.SLOT_AVAILABLE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EscrowService.SlotHold>> holds = ArgumentCaptor.forClass(List.class);
        verify(escrowService).hold(eq(TASK_ID), eq(EMPLOYER_ID), eq(300), holds.capture(), eq("1"));
        assertThat(holds.getValue()).extracting(EscrowService.SlotHold::slotId)
                .containsExactly(101L, 102L);
        assertThat(holds.getValue()).extracting(EscrowService.SlotHold::amount)
                .containsOnly(5000L);

        verify(taskDetailCache).evict(TASK_ID);
    }

    /** 已发布任务再次发布：幂等成功，不重复建名额 / 托管。 */
    @Test
    void publishAlreadyPublishedTaskShouldBeIdempotent() {
        Task published = draftTask();
        published.setPublishedAt(LocalDateTime.now().minusHours(1));
        when(taskMapper.selectById(TASK_ID)).thenReturn(published);
        when(qualificationGate.isPublishEligible(EMPLOYER_ID)).thenReturn(true);

        taskPublishService.publish(EMPLOYER, TASK_ID);

        verify(taskMapper, never()).update(any(), any());
        verify(taskSlotMapper, never()).insert(any(TaskSlot.class));
        verify(escrowService, never()).hold(any(), any(), anyInt(), anyList(), any());
    }

    /** 已撤销任务再发布：409 终态拒绝，不能落入「重复发布」幂等分支。 */
    @Test
    void publishCancelledTaskShouldBeRejected() {
        Task cancelled = draftTask();
        cancelled.setCancelledAt(LocalDateTime.now().minusMinutes(1));
        when(taskMapper.selectById(TASK_ID)).thenReturn(cancelled);
        when(qualificationGate.isPublishEligible(EMPLOYER_ID)).thenReturn(true);

        assertThatThrownBy(() -> taskPublishService.publish(EMPLOYER, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));

        verify(taskMapper, never()).update(any(), any());
        verify(taskSlotMapper, never()).insert(any(TaskSlot.class));
        verify(escrowService, never()).hold(any(), any(), anyInt(), anyList(), any());
    }

    /** 资质门禁开启且无资质：403，不动任何数据。 */
    @Test
    void publishWithoutQualificationShouldBeForbidden() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(draftTask());
        when(qualificationGate.isPublishEligible(EMPLOYER_ID)).thenReturn(false);

        assertThatThrownBy(() -> taskPublishService.publish(EMPLOYER, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(taskMapper, never()).update(any(), any());
        verify(escrowService, never()).hold(any(), any(), anyInt(), anyList(), any());
    }

    /** 草稿的开始时间已过期：400 INVALID_PARAMETER，不抢锁。 */
    @Test
    void publishExpiredDraftShouldBeRejected() {
        Task expired = draftTask();
        expired.setStartTime(LocalDateTime.now().minusDays(1));
        expired.setEndTime(LocalDateTime.now().plusDays(1));
        when(taskMapper.selectById(TASK_ID)).thenReturn(expired);
        when(qualificationGate.isPublishEligible(EMPLOYER_ID)).thenReturn(true);

        assertThatThrownBy(() -> taskPublishService.publish(EMPLOYER, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER));

        verify(taskMapper, never()).update(any(), any());
    }

    /** 抢锁 0 行但回读已发布：并发下的另一个赢家已提交，按幂等成功返回。 */
    @Test
    void publishLosingRaceToPublishedWinnerShouldSucceed() {
        Task published = draftTask();
        published.setPublishedAt(LocalDateTime.now());
        when(taskMapper.selectById(TASK_ID)).thenReturn(draftTask(), published);
        when(qualificationGate.isPublishEligible(EMPLOYER_ID)).thenReturn(true);
        when(taskMapper.update(any(), any())).thenReturn(0);

        taskPublishService.publish(EMPLOYER, TASK_ID);

        verify(taskSlotMapper, never()).insert(any(TaskSlot.class));
        verify(escrowService, never()).hold(any(), any(), anyInt(), anyList(), any());
    }

    /** 抢锁 0 行且回读仍未发布：并发冲突 409。 */
    @Test
    void publishLosingRaceWithoutWinnerShouldConflict() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(draftTask(), draftTask());
        when(qualificationGate.isPublishEligible(EMPLOYER_ID)).thenReturn(true);
        when(taskMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> taskPublishService.publish(EMPLOYER, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
    }

    /** 余额不足由资金域抛出，发布层不吞异常（事务回滚由调用边界保证）。 */
    @Test
    void publishShouldPropagateInsufficientBalance() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(draftTask());
        when(qualificationGate.isPublishEligible(EMPLOYER_ID)).thenReturn(true);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(taskSlotMapper.insert(any(TaskSlot.class))).thenAnswer(invocation -> {
            TaskSlot slot = invocation.getArgument(0);
            slot.setId(101L);
            return 1;
        });
        when(escrowService.hold(any(), anyLong(), anyInt(), anyList(), any()))
                .thenThrow(new ApiException(ErrorCode.INSUFFICIENT_BALANCE, "可用余额不足"));

        assertThatThrownBy(() -> taskPublishService.publish(EMPLOYER, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));

        verify(taskDetailCache, never()).evict(any());
    }

    /** 非雇主身份：403。 */
    @Test
    void workerShouldNotPublish() {
        LoginUser worker = new LoginUser(1002L, "13900139000", UserRole.WORKER, IdentityType.WORKER);

        assertThatThrownBy(() -> taskPublishService.publish(worker, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    /** 任务不存在：404。 */
    @Test
    void publishMissingTaskShouldBeNotFound() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(null);

        assertThatThrownBy(() -> taskPublishService.publish(EMPLOYER, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 他人任务：403。 */
    @Test
    void publishOtherEmployerTaskShouldBeForbidden() {
        Task task = draftTask();
        task.setEmployerId(2002L);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);

        assertThatThrownBy(() -> taskPublishService.publish(EMPLOYER, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private static Task draftTask() {
        LocalDateTime now = LocalDateTime.now();
        Task task = new Task();
        task.setId(TASK_ID);
        task.setEmployerId(EMPLOYER_ID);
        task.setTitle("周末小区派单");
        task.setDescription("帮忙派发传单");
        task.setCategory("地推");
        task.setUnitPrice(5000L);
        task.setSlotCount(2);
        task.setStartTime(now.plusDays(1));
        task.setEndTime(now.plusDays(2));
        task.setWorkLocation("北京市朝阳区");
        task.setPublishedAt(null);
        task.setCancelledAt(null);
        task.setRecruitingClosed(false);
        return task;
    }
}
