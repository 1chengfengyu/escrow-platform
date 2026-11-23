package com.escrow.platform.task.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.task.domain.SlotStateMachine;
import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.TaskSlot;
import com.escrow.platform.task.infrastructure.mapper.TaskSlotMapper;

import java.util.List;

/**
 * 名额状态跃迁的公共写法（撤单与到期共用，用户裁决 ④：一处判定、两处调用）。
 *
 * <p>所有跃迁都先做条件更新（{@code WHERE id=? AND status=?}），受影响行数只用于
 * 判断是否由本次请求完成；目标状态一律由 {@link SlotStateMachine#transit} 给出，
 * 禁止在 Service 里直接写 {@code setStatus} 常量。</p>
 *
 * @since V2
 */
final class TaskSlotTransitions {

    private TaskSlotTransitions() {
    }

    /**
     * 把退款成功的名额从待占用迁到已释放。
     *
     * <p>抢不到（0 行）说明别的路径已处理过，静默跳过，不重复跃迁。</p>
     *
     * @param mapper  名额 Mapper
     * @param slotIds 名额 ID
     */
    static void release(TaskSlotMapper mapper, List<Long> slotIds) {
        SlotStatus target = SlotStateMachine.transit(SlotStatus.SLOT_AVAILABLE,
                SlotStateMachine.Event.RELEASE);
        for (Long slotId : slotIds) {
            mapper.update(null, new LambdaUpdateWrapper<TaskSlot>()
                    .eq(TaskSlot::getId, slotId)
                    .eq(TaskSlot::getStatus, SlotStatus.SLOT_AVAILABLE)
                    .set(TaskSlot::getStatus, target));
        }
    }
}
