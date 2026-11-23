package com.escrow.platform.task.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.common.chat.application.ChatEventDispatcher;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.engagement.application.ApplicationRejectionService;
import com.escrow.platform.fund.application.EscrowService;
import com.escrow.platform.fund.domain.JournalTriggerType;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.domain.TaskCancelEligibility;
import com.escrow.platform.task.domain.TaskCancelEligibilityClassifier;
import com.escrow.platform.task.domain.TaskSlot;
import com.escrow.platform.task.domain.TaskStatusFacts;
import com.escrow.platform.task.infrastructure.cache.TaskDetailCache;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
import com.escrow.platform.task.infrastructure.mapper.TaskSlotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 撤单：退款 + 释放名额 + 状态收口（FR-TASK.CANCEL.01~.02、FR-ESCROW.REFUND.01）。
 *
 * <p><b>顺序不可换</b>：先抢占 {@code cancelled_at}（条件更新），再分类名额集合，
 * 再逐笔退款（先条件抢占托管明细），最后把成功退款的名额从待占用迁到已释放。
 * 先放名额后退款会留下「名额没了、钱还在冻结」的窗口。</p>
 *
 * <p>可撤前提由 {@link TaskCancelEligibilityClassifier} 判定，<b>不在这里另写一份</b>
 * （用户裁决 8A）：全部待占用才可撤；有活跃名额 → {@code TASK_HAS_ACTIVE_SLOT}；
 * 草稿 / 零名额 / 混合情形 → {@code TASK_NOT_CANCELABLE}。</p>
 *
 * <p><b>幂等</b>：重复撤单时抢占 0 行，回读发现 {@code cancelled_at} 非空即返回成功，
 * 不重复退款。记账业务键为 {@code taskId:CANCEL}，退款批次另有唯一键兜底。</p>
 *
 * @since V2
 */
@Service
@RequiredArgsConstructor
public class TaskCancelService {

    private static final String NOT_EMPLOYER_MESSAGE = "仅雇主身份可撤单";
    private static final String TASK_NOT_FOUND_MESSAGE = "任务不存在";
    private static final String NOT_OWNER_MESSAGE = "无权撤销他人任务";
    private static final String ACTIVE_SLOT_MESSAGE = "任务存在进行中的名额，不可撤单";
    private static final String NOT_CANCELABLE_MESSAGE = "当前任务不可撤单";
    private static final String CONFLICT_MESSAGE = "操作冲突，请稍后重试";

    private final TaskMapper taskMapper;
    private final TaskSlotMapper taskSlotMapper;
    private final EscrowService escrowService;
    private final TaskDetailCache taskDetailCache;
    /** 撤单后收口待处理报名（FR-SIGN.APPLY.08）：跨域只调 engagement 域 Service 入口。 */
    private final ApplicationRejectionService applicationRejectionService;
    /** 私聊事件（CARD-11）：撤单成功后归档该任务全部会话，失败不影响撤单。 */
    private final ChatEventDispatcher chatEventDispatcher;

    /**
     * 撤单。
     *
     * @param current 会话主体，必须是该任务的雇主
     * @param taskId  任务 ID
     * @throws ApiException 403 身份 / 归属；404 不存在；409 不可撤 / 有活跃名额 / 并发冲突
     */
    @Transactional
    public void cancel(LoginUser current, Long taskId) {
        if (current == null || current.currentIdentity() != IdentityType.EMPLOYER) {
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_EMPLOYER_MESSAGE);
        }

        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, TASK_NOT_FOUND_MESSAGE);
        }
        if (!task.getEmployerId().equals(current.userId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_OWNER_MESSAGE);
        }

        LocalDateTime now = LocalDateTime.now();
        int claimed = taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, taskId)
                .isNotNull(Task::getPublishedAt)
                .isNull(Task::getCancelledAt)
                .eq(Task::getRecruitingClosed, false)
                .set(Task::getCancelledAt, now));
        if (claimed != 1) {
            Task latest = taskMapper.selectById(taskId);
            if (latest != null && latest.getCancelledAt() != null) {
                return;
            }
            if (latest == null) {
                throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, TASK_NOT_FOUND_MESSAGE);
            }
            if (latest.getPublishedAt() == null) {
                throw new ApiException(ErrorCode.TASK_NOT_CANCELABLE, NOT_CANCELABLE_MESSAGE);
            }
            // 已被到期限流抢走或另一笔撤单正在提交
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, CONFLICT_MESSAGE);
        }

        List<TaskSlot> slots = taskSlotMapper.selectList(new LambdaQueryWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, taskId));

        TaskStatusFacts facts = factsOf(slots, now, task.getEndTime());
        TaskCancelEligibility eligibility = TaskCancelEligibilityClassifier.classify(facts);
        if (eligibility == TaskCancelEligibility.HAS_ACTIVE_SLOT) {
            throw new ApiException(ErrorCode.TASK_HAS_ACTIVE_SLOT, ACTIVE_SLOT_MESSAGE);
        }
        if (eligibility == TaskCancelEligibility.NOT_CANCELABLE) {
            throw new ApiException(ErrorCode.TASK_NOT_CANCELABLE, NOT_CANCELABLE_MESSAGE);
        }

        List<Long> slotIds = slots.stream().map(TaskSlot::getId).toList();
        EscrowService.RefundResult result = escrowService.refund(
                taskId, taskId + ":CANCEL", current.userId(), slotIds, JournalTriggerType.USER);
        TaskSlotTransitions.release(taskSlotMapper, result.refundedSlotIds());

        // FR-SIGN.APPLY.08：撤单后该任务全部待处理报名置为已拒绝
        applicationRejectionService.rejectPendingOfTask(taskId);

        // 任务终态：该任务全部会话归档（afterCommit，失败只记日志）
        chatEventDispatcher.archiveByTaskAfterCommit(taskId);

        TaskCacheEviction.afterCommit(taskDetailCache, taskId);
    }

    /** 把名额集合聚合成 {@link TaskStatusFacts}（本卡只有待占用 / 终态两类可能）。 */
    static TaskStatusFacts factsOf(List<TaskSlot> slots, LocalDateTime now, LocalDateTime endTime) {
        int available = 0;
        int released = 0;
        int refunded = 0;
        int completed = 0;
        int occupied = 0;
        int inProgress = 0;
        int delivered = 0;
        int pendingArbitration = 0;
        int pendingSettlement = 0;
        for (TaskSlot slot : slots) {
            SlotStatus status = slot.getStatus();
            switch (status) {
                case SLOT_AVAILABLE -> available++;
                case SLOT_RELEASED -> released++;
                case SLOT_REFUNDED -> refunded++;
                case SLOT_COMPLETED -> completed++;
                case SLOT_OCCUPIED -> occupied++;
                case SLOT_IN_PROGRESS -> inProgress++;
                case SLOT_DELIVERED -> delivered++;
                case SLOT_PENDING_ARBITRATION -> pendingArbitration++;
                case SLOT_PENDING_SETTLEMENT -> pendingSettlement++;
                default -> throw new IllegalStateException("未知名额状态 " + status);
            }
        }
        return new TaskStatusFacts(false, false, !now.isBefore(endTime),
                available, occupied, inProgress, delivered,
                pendingArbitration, pendingSettlement, completed, refunded, released, 0);
    }
}
