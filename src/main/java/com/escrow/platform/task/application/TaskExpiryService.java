package com.escrow.platform.task.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.engagement.application.ApplicationRejectionService;
import com.escrow.platform.fund.application.EscrowService;
import com.escrow.platform.fund.domain.JournalTriggerType;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.domain.TaskSlot;
import com.escrow.platform.task.infrastructure.cache.TaskDetailCache;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
import com.escrow.platform.task.infrastructure.mapper.TaskSlotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 到期收口：关闭招募 + 退款待占用名额（FR-TASK.EXPIRE.01~.02、FR-ESCROW.REFUND.01）。
 *
 * <p><b>唯一实现</b>（用户裁决 ⑦ / ④）：{@code @Scheduled} 扫描与
 * {@code POST /api/tasks/{id}/expire} 都调用 {@link #expireTask(Long)}，
 * 不允许两处各写一份判定——否则半年后判定条件必然分叉。</p>
 *
 * <p>与撤单的区别：到期只收口「待占用」名额，已占用 / 履约中的名额继续走完，
 * 因此这里不做 {@code TaskCancelEligibilityClassifier} 的全量分类，
 * 也不写 {@code cancelled_at}。</p>
 *
 * <p>幂等：条件更新 {@code WHERE recruiting_closed=0 ...}；0 行即已收口或未到期，返回 {@code false}。
 * V2 未引 ShedLock，单实例部署下 {@code @Scheduled} 可接受（卡片 §10 已知偏差）。</p>
 *
 * @since V2
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExpiryService {

    /** 单次扫描最多处理的任务数（避免一次事务扫全表）。 */
    private static final int SCAN_LIMIT = 100;

    private static final String NOT_EMPLOYER_MESSAGE = "仅雇主身份可收口任务";
    private static final String TASK_NOT_FOUND_MESSAGE = "任务不存在";
    private static final String NOT_OWNER_MESSAGE = "无权收口他人任务";
    private static final String NOT_EXPIRED_MESSAGE = "任务尚未到期，无法收口";

    private final TaskMapper taskMapper;
    private final TaskSlotMapper taskSlotMapper;
    private final EscrowService escrowService;
    private final TaskDetailCache taskDetailCache;
    /** 到期后收口待处理报名（FR-SIGN.APPLY.08）：跨域只调 engagement 域 Service 入口。 */
    private final ApplicationRejectionService applicationRejectionService;

    /**
     * 扫描已到期但仍开放招募的任务 ID。
     *
     * @return 最多 {@value #SCAN_LIMIT} 个任务 ID
     */
    public List<Long> findOverdueTaskIds() {
        List<Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .select(Task::getId)
                .isNotNull(Task::getPublishedAt)
                .isNull(Task::getCancelledAt)
                .eq(Task::getRecruitingClosed, false)
                .le(Task::getEndTime, LocalDateTime.now())
                .last("LIMIT " + SCAN_LIMIT));
        return tasks.stream().map(Task::getId).toList();
    }

    /**
     * 到期收口一个任务：退款并释放全部待占用名额。
     *
     * @param taskId 任务 ID
     * @return {@code true} 本次完成收口；{@code false} 已收口 / 未到期 / 已撤单 / 不存在
     */
    @Transactional
    public boolean expireTask(Long taskId) {
        LocalDateTime now = LocalDateTime.now();
        int claimed = taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, taskId)
                .isNotNull(Task::getPublishedAt)
                .isNull(Task::getCancelledAt)
                .eq(Task::getRecruitingClosed, false)
                .le(Task::getEndTime, now)
                .set(Task::getRecruitingClosed, true));
        if (claimed != 1) {
            return false;
        }

        Task task = taskMapper.selectById(taskId);
        List<TaskSlot> slots = taskSlotMapper.selectList(new LambdaQueryWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getStatus, SlotStatus.SLOT_AVAILABLE));

        if (!slots.isEmpty()) {
            List<Long> slotIds = slots.stream().map(TaskSlot::getId).toList();
            EscrowService.RefundResult result = escrowService.refund(
                    taskId, taskId + ":EXPIRE", task.getEmployerId(), slotIds, JournalTriggerType.SYSTEM);
            TaskSlotTransitions.release(taskSlotMapper, result.refundedSlotIds());
        }

        // FR-SIGN.APPLY.08：到期后该任务全部待处理报名置为已拒绝（无待处理报名时为 0 条）
        int rejected = applicationRejectionService.rejectPendingOfTask(taskId);

        TaskCacheEviction.afterCommit(taskDetailCache, taskId);
        log.info("任务到期收口完成 taskId={} releasedSlots={} rejected={}",
                taskId, slots.size(), rejected);
        return true;
    }

    /**
     * 手动收口入口（演示 / 运维用，卡片 §2 目标 2）。
     *
     * <p>额外做归属与到期校验：手动接口不能被用来提前结束任务。</p>
     *
     * @param current 会话主体
     * @param taskId  任务 ID
     * @throws ApiException 403 身份 / 归属；404 不存在；409 尚未到期
     */
    @Transactional
    public void expireByEmployer(LoginUser current, Long taskId) {
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
        if (task.getEndTime() == null || task.getEndTime().isAfter(LocalDateTime.now())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, NOT_EXPIRED_MESSAGE);
        }
        expireTask(taskId);
    }
}
