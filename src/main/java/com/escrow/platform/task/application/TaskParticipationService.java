package com.escrow.platform.task.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.domain.TaskSlot;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
import com.escrow.platform.task.infrastructure.mapper.TaskSlotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 跨域参与事实（FR-SIGN.APPLY.01~.05）。
 *
 * <p>报名域判断「任务是否可报名」需要三类事实：归属（是否自己的任务）、
 * 发布 / 撤单 / 结束时间、待占用名额数。它们都在 task 域，
 * 按 project_rules §3 只能通过本 Service 入口读取，禁止 engagement 直查 task / task_slot。</p>
 *
 * <p>除 {@link #lockFactsOf(Long)} 会对任务行加排他锁外，其余方法只读、不写任何表；
 * 名额占用归 {@link TaskSlotOccupancyService}（v3-c2）。</p>
 *
 * @since V3
 */
@Service
@RequiredArgsConstructor
public class TaskParticipationService {

    private final TaskMapper taskMapper;
    private final TaskSlotMapper taskSlotMapper;

    /**
     * 读取报名守卫所需的任务事实。
     *
     * @param taskId 任务 ID
     * @return 任务归属、时间与待占用名额数
     * @throws ApiException 任务不存在（404 {@code RESOURCE_NOT_FOUND}）
     */
    public ParticipationFacts factsOf(Long taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        long availableSlots = taskSlotMapper.selectCount(new LambdaQueryWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getStatus, SlotStatus.SLOT_AVAILABLE));
        return new ParticipationFacts(task.getId(), task.getEmployerId(), task.getPublishedAt(),
                task.getCancelledAt(), task.getEndTime(), availableSlots);
    }

    /**
     * 以排他锁读取任务事实（录用专用，FR-SIGN.HIRE.01~.04）。
     *
     * <p><b>为什么必须加锁</b>：录用要同时改报名、名额、签约三处。若不加锁，
     * 两笔并发录用会在「占名额」与「满员收口批量拒绝报名」上互相等待，形成死锁
     * （一笔持名额行等报名行，另一笔持报名行等名额行）。撤单 / 到期同样先锁任务行，
     * 三条路径锁序一致 → 同一任务的录用、撤单、到期串行执行。</p>
     *
     * <p>只做 {@code SELECT ... FOR UPDATE}，不写任务字段；任务状态仍是派生量。</p>
     *
     * @param taskId 任务 ID
     * @return 与 {@link #factsOf(Long)} 同口径的事实快照
     * @throws ApiException 任务不存在（404 {@code RESOURCE_NOT_FOUND}）
     */
    @Transactional
    public ParticipationFacts lockFactsOf(Long taskId) {
        Task task = taskMapper.selectOne(new LambdaQueryWrapper<Task>()
                .eq(Task::getId, taskId)
                .last("FOR UPDATE"));
        if (task == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        long availableSlots = taskSlotMapper.selectCount(new LambdaQueryWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getStatus, SlotStatus.SLOT_AVAILABLE));
        return new ParticipationFacts(task.getId(), task.getEmployerId(), task.getPublishedAt(),
                task.getCancelledAt(), task.getEndTime(), availableSlots);
    }

    /**
     * 报名守卫事实快照。
     *
     * @param taskId         任务 ID
     * @param employerId     雇主用户 ID
     * @param publishedAt    发布时间；NULL = 草稿
     * @param cancelledAt    撤单时间；NULL = 未撤单
     * @param endTime        结束时间
     * @param availableSlots 待占用名额数
     */
    public record ParticipationFacts(Long taskId, Long employerId, LocalDateTime publishedAt,
                                     LocalDateTime cancelledAt, LocalDateTime endTime,
                                     long availableSlots) {
    }
}
