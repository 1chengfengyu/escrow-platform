package com.escrow.platform.task.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.common.chat.application.ChatEventDispatcher;
import com.escrow.platform.task.domain.TaskSlot;
import com.escrow.platform.task.infrastructure.cache.TaskDetailCache;
import com.escrow.platform.task.infrastructure.mapper.TaskSlotMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TaskSlotOccupancyService} 名额终态迁移与会话归档单测（FR-MSG.CHAT.01）。
 *
 * <p>归档判定挂在名额终态迁移这一个点上，验收放款、仲裁放款、零实付、
 * 裁决退款与打款重试成功都经过这里，因此只需验证「终态迁移 + 无活跃名额 ⇒ 归档」。</p>
 *
 * @since V11
 */
@ExtendWith(MockitoExtension.class)
class TaskSlotOccupancyServiceTest {

    private static final Long TASK_ID = 20001L;
    private static final Long SLOT_ID = 30001L;

    @Mock
    private TaskSlotMapper taskSlotMapper;

    @Mock
    private TaskDetailCache taskDetailCache;

    @Mock
    private ChatEventDispatcher chatEventDispatcher;

    @InjectMocks
    private TaskSlotOccupancyService service;

    /** 条件更新与计数查询用 Lambda 构造器，纯单测需先初始化 {@code TableInfo} 缓存。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                TaskSlot.class);
    }

    /** 打款成功（含重试成功）：任务已无活跃名额 → 归档全部会话。 */
    @Test
    void completeAfterPayoutShouldArchiveWhenTaskHasNoActiveSlot() {
        when(taskSlotMapper.update(any(), any())).thenReturn(1);
        when(taskSlotMapper.selectCount(any())).thenReturn(0L);

        service.completeAfterPayout(TASK_ID, SLOT_ID);

        verify(chatEventDispatcher).archiveByTaskAfterCommit(TASK_ID);
    }

    /** 多名额任务仍有名额在推进：不得提前归档会话。 */
    @Test
    void completeAfterPayoutShouldNotArchiveWhenAnotherSlotStillActive() {
        when(taskSlotMapper.update(any(), any())).thenReturn(1);
        when(taskSlotMapper.selectCount(any())).thenReturn(1L);

        service.completeAfterPayout(TASK_ID, SLOT_ID);

        verify(chatEventDispatcher, never()).archiveByTaskAfterCommit(anyLong());
    }

    /** 裁决零实付完结：同样在终态迁移后归档。 */
    @Test
    void completeArbitrationShouldArchiveWhenTaskHasNoActiveSlot() {
        when(taskSlotMapper.update(any(), any())).thenReturn(1);
        when(taskSlotMapper.selectCount(any())).thenReturn(0L);

        service.completeArbitration(TASK_ID, SLOT_ID);

        verify(chatEventDispatcher).archiveByTaskAfterCommit(TASK_ID);
    }

    /** 裁决退款：名额进入已退款终态后归档。 */
    @Test
    void refundArbitrationShouldArchiveWhenTaskHasNoActiveSlot() {
        when(taskSlotMapper.update(any(), any())).thenReturn(1);
        when(taskSlotMapper.selectCount(any())).thenReturn(0L);

        service.refundArbitration(TASK_ID, SLOT_ID);

        verify(chatEventDispatcher).archiveByTaskAfterCommit(TASK_ID);
    }

    /** 零实付验收：不经过打款，也在名额完结时归档。 */
    @Test
    void completeOnZeroPayShouldArchiveWhenTaskHasNoActiveSlot() {
        when(taskSlotMapper.update(any(), any())).thenReturn(1);
        when(taskSlotMapper.selectCount(any())).thenReturn(0L);

        service.completeOnZeroPay(TASK_ID, SLOT_ID);

        verify(chatEventDispatcher).archiveByTaskAfterCommit(TASK_ID);
    }

    /** 状态守卫失败（名额已被别的路径推进）：抛异常且绝不归档。 */
    @Test
    void completeAfterPayoutShouldThrowAndNotArchiveWhenTransitionFails() {
        when(taskSlotMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.completeAfterPayout(TASK_ID, SLOT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("名额不在待结算状态");

        verify(chatEventDispatcher, never()).archiveByTaskAfterCommit(anyLong());
    }

    /** 非终态迁移（验收通过转待结算）不触发归档判定。 */
    @Test
    void acceptForSettlementShouldNotArchive() {
        when(taskSlotMapper.update(any(), any())).thenReturn(1);

        service.acceptForSettlement(TASK_ID, SLOT_ID);

        verify(taskSlotMapper, never()).selectCount(any());
        verify(chatEventDispatcher, never()).archiveByTaskAfterCommit(anyLong());
    }
}
