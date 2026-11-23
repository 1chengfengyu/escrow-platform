package com.escrow.platform.task.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.common.chat.application.ChatEventDispatcher;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.engagement.application.ApplicationRejectionService;
import com.escrow.platform.fund.application.EscrowService;
import com.escrow.platform.fund.domain.JournalTriggerType;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 撤单服务测试（v2-c5）：分类器映射、退款与释放、重复撤单幂等。
 *
 * @since V2
 */
@ExtendWith(MockitoExtension.class)
class TaskCancelServiceTest {

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
    private ApplicationRejectionService applicationRejectionService;

    @Mock
    private ChatEventDispatcher chatEventDispatcher;

    @InjectMocks
    private TaskCancelService taskCancelService;

    /** 抢锁与名额条件更新用 Lambda 构造器，纯单测需先初始化 {@code TableInfo} 缓存。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Task.class);
        TableInfoHelper.initTableInfo(assistant, TaskSlot.class);
    }

    /** 全部名额待占用：退款 → 释放名额 → 收口待处理报名 → 清缓存。 */
    @Test
    void cancelShouldRefundAndReleaseSlots() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask());
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(taskSlotMapper.selectList(any())).thenReturn(List.of(
                slot(11L, SlotStatus.SLOT_AVAILABLE), slot(12L, SlotStatus.SLOT_AVAILABLE)));
        when(escrowService.refund(eq(TASK_ID), eq("1:CANCEL"), eq(EMPLOYER_ID), anyList(),
                eq(JournalTriggerType.USER)))
                .thenReturn(new EscrowService.RefundResult(10000L, List.of(11L, 12L)));
        when(taskSlotMapper.update(any(), any())).thenReturn(1);

        taskCancelService.cancel(EMPLOYER, TASK_ID);

        verify(escrowService).refund(TASK_ID, "1:CANCEL", EMPLOYER_ID, List.of(11L, 12L),
                JournalTriggerType.USER);
        verify(taskSlotMapper, times(2)).update(any(), any());
        // FR-SIGN.APPLY.08：撤单后由 engagement 域统一拒绝待处理报名
        verify(applicationRejectionService).rejectPendingOfTask(TASK_ID);
        // CARD-11：撤单成功后登记归档事件
        verify(chatEventDispatcher).archiveByTaskAfterCommit(TASK_ID);
        verify(taskDetailCache).evict(TASK_ID);
    }

    /** 存在已占用名额：409 TASK_HAS_ACTIVE_SLOT，不退款。 */
    @Test
    void cancelWithActiveSlotShouldBeRejected() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask());
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(taskSlotMapper.selectList(any())).thenReturn(List.of(
                slot(11L, SlotStatus.SLOT_AVAILABLE), slot(12L, SlotStatus.SLOT_OCCUPIED)));

        assertThatThrownBy(() -> taskCancelService.cancel(EMPLOYER, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TASK_HAS_ACTIVE_SLOT));

        verify(escrowService, never()).refund(any(), any(), any(), anyList(), any());
        verify(applicationRejectionService, never()).rejectPendingOfTask(any());
        verify(taskDetailCache, never()).evict(any());
    }

    /** 全部名额已终态：409 TASK_NOT_CANCELABLE。 */
    @Test
    void cancelAllTerminalShouldNotBeCancelable() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask());
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(taskSlotMapper.selectList(any())).thenReturn(List.of(
                slot(11L, SlotStatus.SLOT_RELEASED), slot(12L, SlotStatus.SLOT_RELEASED)));

        assertThatThrownBy(() -> taskCancelService.cancel(EMPLOYER, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TASK_NOT_CANCELABLE));

        verify(escrowService, never()).refund(any(), any(), any(), anyList(), any());
    }

    /** 重复撤单：抢锁 0 行 + 回读已撤单 → 幂等成功，不重复退款。 */
    @Test
    void cancelTwiceShouldBeIdempotent() {
        Task task = publishedTask();
        Task cancelled = publishedTask();
        cancelled.setCancelledAt(LocalDateTime.now());
        when(taskMapper.selectById(TASK_ID)).thenReturn(task, cancelled);
        when(taskMapper.update(any(), any())).thenReturn(0);

        taskCancelService.cancel(EMPLOYER, TASK_ID);

        verify(escrowService, never()).refund(any(), any(), any(), anyList(), any());
        // 重复撤单不重复归档
        verify(chatEventDispatcher, never()).archiveByTaskAfterCommit(any());
    }

    /** 草稿撤单：抢锁 0 行 + 回读未发布 → TASK_NOT_CANCELABLE。 */
    @Test
    void cancelDraftShouldNotBeCancelable() {
        Task draft = publishedTask();
        draft.setPublishedAt(null);
        when(taskMapper.selectById(TASK_ID)).thenReturn(draft, draft);
        when(taskMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> taskCancelService.cancel(EMPLOYER, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TASK_NOT_CANCELABLE));
    }

    /** 抢锁 0 行但任务仍处于招募中：并发冲突 409。 */
    @Test
    void cancelLosingRaceShouldConflict() {
        Task published = publishedTask();
        when(taskMapper.selectById(TASK_ID)).thenReturn(published, published);
        when(taskMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> taskCancelService.cancel(EMPLOYER, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
    }

    /** 非雇主：403。 */
    @Test
    void workerShouldNotCancel() {
        LoginUser worker = new LoginUser(1002L, "13900139000", UserRole.WORKER, IdentityType.WORKER);

        assertThatThrownBy(() -> taskCancelService.cancel(worker, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    /** 他人任务：403。 */
    @Test
    void cancelOtherEmployerTaskShouldBeForbidden() {
        Task task = publishedTask();
        task.setEmployerId(2002L);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);

        assertThatThrownBy(() -> taskCancelService.cancel(EMPLOYER, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private static Task publishedTask() {
        LocalDateTime now = LocalDateTime.now();
        Task task = new Task();
        task.setId(TASK_ID);
        task.setEmployerId(EMPLOYER_ID);
        task.setSlotCount(2);
        task.setStartTime(now.minusDays(1));
        task.setEndTime(now.plusDays(1));
        task.setPublishedAt(now.minusHours(1));
        task.setCancelledAt(null);
        task.setRecruitingClosed(false);
        return task;
    }

    private static TaskSlot slot(Long id, SlotStatus status) {
        TaskSlot slot = new TaskSlot();
        slot.setId(id);
        slot.setTaskId(TASK_ID);
        slot.setStatus(status);
        return slot;
    }
}
