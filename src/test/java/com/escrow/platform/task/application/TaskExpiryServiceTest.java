package com.escrow.platform.task.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
 * 到期收口测试（v2-c5）：只退款待占用名额、重复扫描幂等、手动入口的到期守卫。
 *
 * @since V2
 */
@ExtendWith(MockitoExtension.class)
class TaskExpiryServiceTest {

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

    @InjectMocks
    private TaskExpiryService taskExpiryService;

    /** 抢锁与名额条件更新用 Lambda 构造器，纯单测需先初始化 {@code TableInfo} 缓存。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Task.class);
        TableInfoHelper.initTableInfo(assistant, TaskSlot.class);
    }

    /** 到期收口：退款 + 释放待占用名额 + 收口待处理报名 + 清缓存。 */
    @Test
    void expireShouldRefundAvailableSlots() {
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask());
        when(taskSlotMapper.selectList(any())).thenReturn(List.of(
                slot(11L, SlotStatus.SLOT_AVAILABLE), slot(12L, SlotStatus.SLOT_AVAILABLE)));
        when(escrowService.refund(eq(TASK_ID), eq("1:EXPIRE"), eq(EMPLOYER_ID), anyList(),
                eq(JournalTriggerType.SYSTEM)))
                .thenReturn(new EscrowService.RefundResult(10000L, List.of(11L, 12L)));
        when(taskSlotMapper.update(any(), any())).thenReturn(1);

        boolean expired = taskExpiryService.expireTask(TASK_ID);

        assertThat(expired).isTrue();
        verify(escrowService).refund(TASK_ID, "1:EXPIRE", EMPLOYER_ID, List.of(11L, 12L),
                JournalTriggerType.SYSTEM);
        verify(taskSlotMapper, times(2)).update(any(), any());
        // FR-SIGN.APPLY.08：到期后由 engagement 域统一拒绝待处理报名
        verify(applicationRejectionService).rejectPendingOfTask(TASK_ID);
        verify(taskDetailCache).evict(TASK_ID);
    }

    /** 已收口 / 未到期：抢锁 0 行，直接返回 false，不退款。 */
    @Test
    void expireAlreadyClosedShouldBeNoop() {
        when(taskMapper.update(any(), any())).thenReturn(0);

        assertThat(taskExpiryService.expireTask(TASK_ID)).isFalse();

        verify(escrowService, never()).refund(any(), any(), any(), anyList(), any());
        verify(applicationRejectionService, never()).rejectPendingOfTask(any());
        verify(taskDetailCache, never()).evict(any());
    }

    /** 到期但没有待占用名额：只收口，不搬钱。 */
    @Test
    void expireWithoutAvailableSlotsShouldStillClose() {
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask());
        when(taskSlotMapper.selectList(any())).thenReturn(List.of());

        assertThat(taskExpiryService.expireTask(TASK_ID)).isTrue();

        verify(escrowService, never()).refund(any(), any(), any(), anyList(), any());
        verify(taskDetailCache).evict(TASK_ID);
    }

    /** 手动收口未到期任务：409 INVALID_STATE_TRANSITION。 */
    @Test
    void manualExpireBeforeEndTimeShouldBeRejected() {
        Task task = publishedTask();
        task.setEndTime(LocalDateTime.now().plusDays(1));
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);

        assertThatThrownBy(() -> taskExpiryService.expireByEmployer(EMPLOYER, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));

        verify(taskMapper, never()).update(any(), any());
    }

    /** 手动收口已到期任务：走同一个 expireTask。 */
    @Test
    void manualExpireShouldDelegateToSameService() {
        Task task = publishedTask();
        task.setEndTime(LocalDateTime.now().minusMinutes(1));
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(taskSlotMapper.selectList(any())).thenReturn(List.of());

        taskExpiryService.expireByEmployer(EMPLOYER, TASK_ID);

        verify(taskMapper).update(any(), any());
        verify(taskDetailCache).evict(TASK_ID);
    }

    /** 手动收口不存在的任务：404。 */
    @Test
    void manualExpireMissingTaskShouldBeNotFound() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(null);

        assertThatThrownBy(() -> taskExpiryService.expireByEmployer(EMPLOYER, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 非雇主：403。 */
    @Test
    void workerShouldNotExpire() {
        LoginUser worker = new LoginUser(1002L, "13900139000", UserRole.WORKER, IdentityType.WORKER);

        assertThatThrownBy(() -> taskExpiryService.expireByEmployer(worker, TASK_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private static Task publishedTask() {
        LocalDateTime now = LocalDateTime.now();
        Task task = new Task();
        task.setId(TASK_ID);
        task.setEmployerId(EMPLOYER_ID);
        task.setEndTime(now.minusMinutes(1));
        task.setPublishedAt(now.minusDays(1));
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
