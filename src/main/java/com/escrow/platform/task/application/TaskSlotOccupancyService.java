package com.escrow.platform.task.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.common.chat.application.ChatEventDispatcher;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.task.domain.SlotStateMachine;
import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.TaskSlot;
import com.escrow.platform.task.infrastructure.cache.TaskDetailCache;
import com.escrow.platform.task.infrastructure.mapper.TaskSlotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 名额占用（FR-SIGN.HIRE.01、FR-SIGN.APPLY.04）。
 *
 * <p>engagement 域只能通过本入口写名额，不直调 {@code TaskSlotMapper}（project_rules §3）。
 * 名额计数不落库：「剩余名额」是「待占用」名额的集合投影（docs/decisions.md #47），
 * 占用一次自然少一，不做任何计数列更新。</p>
 *
 * <p><b>原子占用</b>：{@code UPDATE task_slot SET status='SLOT_OCCUPIED', application_id=?
 * WHERE task_id=? AND status='SLOT_AVAILABLE' ORDER BY seq_no LIMIT 1}，受影响行数为 0
 * 即名额已被抢光 → {@code SLOT_TAKEN}（FR-SIGN.HIRE.03）。禁止「先 SELECT 判断再 UPDATE」。</p>
 *
 * @since V3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSlotOccupancyService {

    private static final String SLOT_TAKEN_MESSAGE = "名额已被占用，请刷新后重试";

    private final TaskSlotMapper taskSlotMapper;
    private final TaskDetailCache taskDetailCache;
    private final ChatEventDispatcher chatEventDispatcher;

    /**
     * 原子占用该任务序号最小的待占用名额。
     *
     * @param taskId        任务 ID
     * @param applicationId 报名 ID，回填到 {@code task_slot.application_id}
     * @return 被占用的名额 ID
     * @throws ApiException 无待占用名额（409 {@code SLOT_TAKEN}）
     */
    @Transactional
    public Long occupyForHire(Long taskId, Long applicationId) {
        SlotStatus target = SlotStateMachine.transit(SlotStatus.SLOT_AVAILABLE,
                SlotStateMachine.Event.OCCUPY);
        int updated = taskSlotMapper.update(null, new LambdaUpdateWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getStatus, SlotStatus.SLOT_AVAILABLE)
                .set(TaskSlot::getStatus, target)
                .set(TaskSlot::getApplicationId, applicationId)
                .last("ORDER BY seq_no LIMIT 1"));
        if (updated != 1) {
            log.info("名额已被抢占 taskId={} applicationId={}", taskId, applicationId);
            throw new ApiException(ErrorCode.SLOT_TAKEN, SLOT_TAKEN_MESSAGE);
        }

        TaskSlot occupied = taskSlotMapper.selectOne(new LambdaQueryWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getApplicationId, applicationId));
        if (occupied == null) {
            throw new IllegalStateException(
                    "名额占用后回查失败 taskId=" + taskId + " applicationId=" + applicationId);
        }
        // 待占用数变化会让任务详情快照过期（登记名额状态 + available 计数）
        TaskCacheEviction.afterCommit(taskDetailCache, taskId);
        log.info("名额已占用 taskId={} slotId={} applicationId={}",
                taskId, occupied.getId(), applicationId);
        return occupied.getId();
    }

    /**
     * 把签约 ID 回填到名额行（SRS 4.1「名额 1 : 0..1 签约」）。
     *
     * <p>签约在占名额之后才插入（要先拿到 slotId），因此回填是同一事务内的第二步更新；
     * 条件更新保证不会覆盖已有值。</p>
     *
     * @param slotId     名额 ID
     * @param contractId 签约 ID
     */
    @Transactional
    public void bindContract(Long slotId, Long contractId) {
        taskSlotMapper.update(null, new LambdaUpdateWrapper<TaskSlot>()
                .eq(TaskSlot::getId, slotId)
                .isNull(TaskSlot::getEngagementId)
                .set(TaskSlot::getEngagementId, contractId));
    }

    /**
     * 开始履约：名额「已占用 → 履约中」（FR-SIGN.START.01）。
     *
     * <p>条件更新附带 {@code task_id} 与 {@code status} 两个谓词：0 行说明签约与名额已经不一致
     * （并发开始 / 取消后又被推进），属服务层守卫漏洞，抛 {@link IllegalStateException} 让事务整体回滚。</p>
     *
     * @param taskId 任务 ID，用于回查缓存失效与归属校验
     * @param slotId 名额 ID
     */
    @Transactional
    public void startPerformance(Long taskId, Long slotId) {
        SlotStatus target = SlotStateMachine.transit(SlotStatus.SLOT_OCCUPIED,
                SlotStateMachine.Event.START);
        int updated = taskSlotMapper.update(null, new LambdaUpdateWrapper<TaskSlot>()
                .eq(TaskSlot::getId, slotId)
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getStatus, SlotStatus.SLOT_OCCUPIED)
                .set(TaskSlot::getStatus, target));
        if (updated != 1) {
            throw new IllegalStateException(
                    "名额不在已占用状态，无法开始履约 taskId=" + taskId + " slotId=" + slotId);
        }
        TaskCacheEviction.afterCommit(taskDetailCache, taskId);
        log.info("名额进入履约中 taskId={} slotId={}", taskId, slotId);
    }

    /**
     * 取消签约：名额「已占用 → 待占用」，并清空报名 ID 与签约 ID（FR-SIGN.CANCEL.01 / CANCEL.04）。
     *
     * <p>目标状态必须是「待占用」而不是「已释放」：取消签约后名额重新开放，
     * 同一名额可被再次录用（FR-SIGN.CANCEL.02）；「已释放」只由撤单 / 到期产生。
     * 托管明细不在本方法内处理——取消不退款，资金保持「已托管」。</p>
     *
     * @param taskId 任务 ID，用于回查缓存失效与归属校验
     * @param slotId 名额 ID
     */
    @Transactional
    public void reopenAfterCancel(Long taskId, Long slotId) {
        SlotStatus target = SlotStateMachine.transit(SlotStatus.SLOT_OCCUPIED,
                SlotStateMachine.Event.REOPEN);
        int updated = taskSlotMapper.update(null, new LambdaUpdateWrapper<TaskSlot>()
                .eq(TaskSlot::getId, slotId)
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getStatus, SlotStatus.SLOT_OCCUPIED)
                .set(TaskSlot::getStatus, target)
                .set(TaskSlot::getApplicationId, null)
                .set(TaskSlot::getEngagementId, null));
        if (updated != 1) {
            throw new IllegalStateException(
                    "名额不在已占用状态，无法取消签约 taskId=" + taskId + " slotId=" + slotId);
        }
        TaskCacheEviction.afterCommit(taskDetailCache, taskId);
        log.info("名额回到待占用 taskId={} slotId={}", taskId, slotId);
    }

    /**
     * 交付：名额「履约中 → 已交付」（FR-DELIVER.SUBMIT.02）。
     *
     * <p>条件更新 0 行说明签约与名额不一致（未经开始即交付 / 已被别的路径推进），
     * 属服务层守卫漏洞，抛 {@link IllegalStateException} 让整个交付事务回滚。</p>
     *
     * @param taskId 任务 ID，用于回查缓存失效与归属校验
     * @param slotId 名额 ID
     */
    @Transactional
    public void deliverForContract(Long taskId, Long slotId) {
        SlotStatus target = SlotStateMachine.transit(SlotStatus.SLOT_IN_PROGRESS,
                SlotStateMachine.Event.DELIVER);
        int updated = taskSlotMapper.update(null, new LambdaUpdateWrapper<TaskSlot>()
                .eq(TaskSlot::getId, slotId)
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getStatus, SlotStatus.SLOT_IN_PROGRESS)
                .set(TaskSlot::getStatus, target));
        if (updated != 1) {
            throw new IllegalStateException(
                    "名额不在履约中状态，无法交付 taskId=" + taskId + " slotId=" + slotId);
        }
        TaskCacheEviction.afterCommit(taskDetailCache, taskId);
        log.info("名额已交付 taskId={} slotId={}", taskId, slotId);
    }

    /**
     * 验收通过：名额「已交付 → 待结算」（FR-ACCEPT.RESULT.01）。
     *
     * <p>实付 &gt; 0 走本方法；实付 = 0 走 {@link #completeOnZeroPay(Long, Long)}。
     * 条件更新 0 行说明名额已被拒收 / 已推进，抛 {@link IllegalStateException} 回滚整个验收事务。</p>
     *
     * @param taskId 任务 ID，用于缓存失效与归属校验
     * @param slotId 名额 ID
     */
    @Transactional
    public void acceptForSettlement(Long taskId, Long slotId) {
        transitFromDelivered(taskId, slotId, SlotStateMachine.Event.ACCEPT, "验收通过");
    }

    /**
     * 零实付验收通过：名额「已交付 → 已完结」（FR-SETTLE.FEE.03）。
     *
     * @param taskId 任务 ID
     * @param slotId 名额 ID
     */
    @Transactional
    public void completeOnZeroPay(Long taskId, Long slotId) {
        transitFromDelivered(taskId, slotId, SlotStateMachine.Event.ZERO_PAY_COMPLETE, "零实付完结");
        archiveIfTaskFinished(taskId);
    }

    /**
     * 雇主拒收：名额「已交付 → 待裁决」（FR-ACCEPT.RESULT.02）。
     *
     * @param taskId 任务 ID
     * @param slotId 名额 ID
     */
    @Transactional
    public void rejectToArbitration(Long taskId, Long slotId) {
        transitFromDelivered(taskId, slotId, SlotStateMachine.Event.REJECT, "拒收转待裁决");
    }

    /**
     * 打款成功：名额「待结算 → 已完结」（FR-PAY.CONFIRM.01）。
     *
     * @param taskId 任务 ID
     * @param slotId 名额 ID
     */
    @Transactional
    public void completeAfterPayout(Long taskId, Long slotId) {
        SlotStatus target = SlotStateMachine.transit(SlotStatus.SLOT_PENDING_SETTLEMENT,
                SlotStateMachine.Event.SETTLEMENT_SUCCEED);
        int updated = taskSlotMapper.update(null, new LambdaUpdateWrapper<TaskSlot>()
                .eq(TaskSlot::getId, slotId)
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getStatus, SlotStatus.SLOT_PENDING_SETTLEMENT)
                .set(TaskSlot::getStatus, target));
        if (updated != 1) {
            throw new IllegalStateException(
                    "名额不在待结算状态，无法完结 taskId=" + taskId + " slotId=" + slotId);
        }
        TaskCacheEviction.afterCommit(taskDetailCache, taskId);
        log.info("名额已完结 taskId={} slotId={}", taskId, slotId);
        archiveIfTaskFinished(taskId);
    }

    /**
     * 对账冲正：名额「已完结 → 待结算」，等待重新出站（V6，FR-RECON.HANDLE.01）。
     *
     * @param taskId 任务 ID
     * @param slotId 名额 ID
     */
    @Transactional
    public void reopenAfterRecon(Long taskId, Long slotId) {
        SlotStatus target = SlotStateMachine.transit(SlotStatus.SLOT_COMPLETED,
                SlotStateMachine.Event.RECON_ROLLBACK);
        int updated = taskSlotMapper.update(null, new LambdaUpdateWrapper<TaskSlot>()
                .eq(TaskSlot::getId, slotId)
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getStatus, SlotStatus.SLOT_COMPLETED)
                .set(TaskSlot::getStatus, target));
        if (updated != 1) {
            throw new IllegalStateException(
                    "名额不在已完结状态，无法对账回退 taskId=" + taskId + " slotId=" + slotId);
        }
        TaskCacheEviction.afterCommit(taskDetailCache, taskId);
        log.warn("名额对账回退为待结算 taskId={} slotId={}", taskId, slotId);
    }

    /**
     * 打款失败且额度用尽：名额「待结算 → 待裁决」。
     *
     * @param taskId 任务 ID
     * @param slotId 名额 ID
     */
    @Transactional
    public void arbitrationAfterPayoutFailure(Long taskId, Long slotId) {
        SlotStatus target = SlotStateMachine.transit(SlotStatus.SLOT_PENDING_SETTLEMENT,
                SlotStateMachine.Event.SETTLEMENT_FAIL);
        int updated = taskSlotMapper.update(null, new LambdaUpdateWrapper<TaskSlot>()
                .eq(TaskSlot::getId, slotId)
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getStatus, SlotStatus.SLOT_PENDING_SETTLEMENT)
                .set(TaskSlot::getStatus, target));
        if (updated != 1) {
            throw new IllegalStateException(
                    "名额不在待结算状态，无法转待裁决 taskId=" + taskId + " slotId=" + slotId);
        }
        TaskCacheEviction.afterCommit(taskDetailCache, taskId);
        log.info("名额打款失败转待裁决 taskId={} slotId={}", taskId, slotId);
    }

    /**
     * 劳动者申诉开争议：名额「已交付 → 待裁决」（V6，FR-DISPUTE.OPEN.01）。
     *
     * @param taskId 任务 ID
     * @param slotId 名额 ID
     */
    @Transactional
    public void openArbitration(Long taskId, Long slotId) {
        transitFromDelivered(taskId, slotId, SlotStateMachine.Event.REJECT, "申诉转待裁决");
    }

    /**
     * 争议撤回：名额「待裁决 → 已交付」，重新进入验收倒计时（V6，FR-DISPUTE.WITHDRAW.01）。
     *
     * @param taskId 任务 ID
     * @param slotId 名额 ID
     */
    @Transactional
    public void withdrawArbitration(Long taskId, Long slotId) {
        transitFromArbitration(taskId, slotId, SlotStateMachine.Event.ARBITRATION_WITHDRAW,
                "争议撤回回到已交付");
    }

    /**
     * 裁决放款：名额「待裁决 → 待结算」（V6，FR-DISPUTE.RESOLVE.01）。
     *
     * @param taskId 任务 ID
     * @param slotId 名额 ID
     */
    @Transactional
    public void releaseArbitration(Long taskId, Long slotId) {
        transitFromArbitration(taskId, slotId, SlotStateMachine.Event.ARBITRATION_RELEASE,
                "裁决放款转待结算");
    }

    /**
     * 裁决放款且净额为 0：名额「待裁决 → 已完结」（V6，零实付边界）。
     *
     * @param taskId 任务 ID
     * @param slotId 名额 ID
     */
    @Transactional
    public void completeArbitration(Long taskId, Long slotId) {
        transitFromArbitration(taskId, slotId, SlotStateMachine.Event.ARBITRATION_ZERO_COMPLETE,
                "裁决零实付完结");
        archiveIfTaskFinished(taskId);
    }

    /**
     * 裁决退款：名额「待裁决 → 已退款」（V6，FR-DISPUTE.RESOLVE.01）。
     *
     * @param taskId 任务 ID
     * @param slotId 名额 ID
     */
    @Transactional
    public void refundArbitration(Long taskId, Long slotId) {
        transitFromArbitration(taskId, slotId, SlotStateMachine.Event.ARBITRATION_REFUND,
                "裁决退款");
        archiveIfTaskFinished(taskId);
    }

    private void transitFromArbitration(Long taskId, Long slotId,
                                        SlotStateMachine.Event event, String action) {
        SlotStatus target = SlotStateMachine.transit(SlotStatus.SLOT_PENDING_ARBITRATION, event);
        int updated = taskSlotMapper.update(null, new LambdaUpdateWrapper<TaskSlot>()
                .eq(TaskSlot::getId, slotId)
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getStatus, SlotStatus.SLOT_PENDING_ARBITRATION)
                .set(TaskSlot::getStatus, target));
        if (updated != 1) {
            throw new IllegalStateException(
                    "名额不在待裁决状态，无法" + action + " taskId=" + taskId + " slotId=" + slotId);
        }
        TaskCacheEviction.afterCommit(taskDetailCache, taskId);
        log.info("{} taskId={} slotId={}", action, taskId, slotId);
    }

    private void transitFromDelivered(Long taskId, Long slotId,
                                      SlotStateMachine.Event event, String action) {
        SlotStatus target = SlotStateMachine.transit(SlotStatus.SLOT_DELIVERED, event);
        int updated = taskSlotMapper.update(null, new LambdaUpdateWrapper<TaskSlot>()
                .eq(TaskSlot::getId, slotId)
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getStatus, SlotStatus.SLOT_DELIVERED)
                .set(TaskSlot::getStatus, target));
        if (updated != 1) {
            throw new IllegalStateException(
                    "名额不在已交付状态，无法" + action + " taskId=" + taskId + " slotId=" + slotId);
        }
        TaskCacheEviction.afterCommit(taskDetailCache, taskId);
        log.info("{} taskId={} slotId={}", action, taskId, slotId);
    }

    /**
     * 读取名额（只读，供验收守卫判断）。不存在返回 {@code null}。
     *
     * @param slotId 名额 ID
     * @return 名额行或 null
     */
    public TaskSlot slotOf(Long slotId) {
        return taskSlotMapper.selectById(slotId);
    }

    /**
     * 是否仍有待占用名额（满员判定，FR-SIGN.HIRE.02）。
     *
     * <p>判据是名额集合的实时投影，不看 {@code recruiting_closed}、也不写任务状态。</p>
     *
     * @param taskId 任务 ID
     * @return {@code true} 仍有「待占用」名额
     */
    public boolean hasAvailableSlot(Long taskId) {
        Long count = taskSlotMapper.selectCount(new LambdaQueryWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, taskId)
                .eq(TaskSlot::getStatus, SlotStatus.SLOT_AVAILABLE));
        return count != null && count > 0;
    }

    /**
     * 是否仍有非终态名额（CARD-11：无则视为任务已到终态，可归档会话）。
     *
     * <p>终态 = 已完成 / 已退款 / 已释放；其余状态都还会继续推进，
     * 此时归档会话会切断仍在履约的双方沟通。</p>
     *
     * @param taskId 任务 ID
     * @return {@code true} 仍存在可推进的名额
     */
    public boolean hasActiveSlot(Long taskId) {
        Long count = taskSlotMapper.selectCount(new LambdaQueryWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, taskId)
                .ne(TaskSlot::getStatus, SlotStatus.SLOT_COMPLETED)
                .ne(TaskSlot::getStatus, SlotStatus.SLOT_REFUNDED)
                .ne(TaskSlot::getStatus, SlotStatus.SLOT_RELEASED));
        return count != null && count > 0;
    }

    /**
     * 名额进入终态后，若该任务已无待推进名额则归档全部会话（FR-MSG.CHAT.01）。
     *
     * <p>挂在名额终态迁移这一个点上，可同时覆盖验收放款、仲裁放款、零实付、
     * 裁决退款与打款重试成功（{@code PayoutDispatchJob} 重试成功后同样走
     * {@link #completeAfterPayout(Long, Long)}），不必在每个上游编排处各补一次。
     * 判定在写入终态之后、同一事务内进行，读到的就是本次迁移后的真实投影。</p>
     *
     * @param taskId 任务 ID
     */
    private void archiveIfTaskFinished(Long taskId) {
        if (!hasActiveSlot(taskId)) {
            chatEventDispatcher.archiveByTaskAfterCommit(taskId);
        }
    }
}
