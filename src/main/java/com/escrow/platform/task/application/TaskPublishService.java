package com.escrow.platform.task.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.fund.application.EscrowService;
import com.escrow.platform.identity.application.QualificationGate;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.task.api.TaskDraftRequest;
import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.domain.TaskSlot;
import com.escrow.platform.task.infrastructure.cache.TaskDetailCache;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
import com.escrow.platform.task.infrastructure.mapper.TaskSlotMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务发布：草稿 → 招募中（FR-TASK.CREATE.04、FR-ESCROW.HOLD.01~.04、FR-ACCT.QUAL.03）。
 *
 * <p><b>发布即托管，一个本地事务</b>（用户裁决 ①）：抢占 {@code published_at} →
 * 生成 N 条名额 → 冻结雇主可用余额 → 建托管单（费率快照）+ N 条明细 → 写记账批次。
 * 任一步失败整事务回滚，不留「已发布未托管」的中间态。</p>
 *
 * <p><b>幂等（裁决 2B）</b>：条件更新 {@code WHERE published_at IS NULL AND cancelled_at IS NULL}
 * 即幂等载体；受影响 0 行则回读——已发布说明是重复请求，直接返回成功；
 * 未发布说明并发冲突，抛 {@code CONCURRENT_MODIFICATION}。任务 ID 天然是记账业务键。</p>
 *
 * <p>发布前重跑 {@link TaskValidator}（裁决 11A，{@code requireFutureStart=true}）：
 * 草稿可能放置很久，{@code startTime} 会过期，只有发布时校验才能保证「招募中的任务一定有效」。</p>
 *
 * @since V2
 */
@Service
public class TaskPublishService {

    private static final String NOT_EMPLOYER_MESSAGE = "仅雇主身份可发布任务";
    private static final String TASK_NOT_FOUND_MESSAGE = "任务不存在";
    private static final String NOT_OWNER_MESSAGE = "无权发布他人任务";
    private static final String QUALIFICATION_MESSAGE = "资质未通过，暂不可发布任务";
    private static final String CANCELLED_MESSAGE = "任务已撤销，不可发布";
    private static final String CONFLICT_MESSAGE = "操作冲突，请稍后重试";
    private static final String INVALID_PARAMETER_MESSAGE = "请求参数不合法";
    private static final String FEE_RATE_MESSAGE = "服务费率配置非法，必须在 0~10000 之间";

    private final TaskMapper taskMapper;
    private final TaskSlotMapper taskSlotMapper;
    private final EscrowService escrowService;
    private final TaskDetailCache taskDetailCache;
    private final QualificationGate qualificationGate;

    /** 无状态校验器，草稿创建 / 编辑 / 发布共用同一份规则。 */
    private final TaskValidator validator = new TaskValidator();

    /** 服务费率快照（万分数），默认 3%（裁决 5A：费率不能由请求方决定）。 */
    private final int feeRate;

    public TaskPublishService(TaskMapper taskMapper,
                              TaskSlotMapper taskSlotMapper,
                              EscrowService escrowService,
                              TaskDetailCache taskDetailCache,
                              QualificationGate qualificationGate,
                              @Value("${escrow.fee.rate:300}") int feeRate) {
        if (feeRate < 0 || feeRate > 10000) {
            throw new IllegalStateException(FEE_RATE_MESSAGE);
        }
        this.taskMapper = taskMapper;
        this.taskSlotMapper = taskSlotMapper;
        this.escrowService = escrowService;
        this.taskDetailCache = taskDetailCache;
        this.qualificationGate = qualificationGate;
        this.feeRate = feeRate;
    }

    /**
     * 发布任务。
     *
     * @param current 会话主体，必须是该任务的雇主
     * @param taskId  任务 ID
     * @throws ApiException 403 身份 / 归属 / 资质；404 任务不存在；
     *                      400 草稿字段已失效；409 已撤销 / 并发冲突
     */
    @Transactional
    public void publish(LoginUser current, Long taskId) {
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
        if (!qualificationGate.isPublishEligible(current.userId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, QUALIFICATION_MESSAGE);
        }
        // 已撤销是终态：必须先于幂等分支判断，否则重复调用会被当成「重复发布」静默成功
        if (task.getCancelledAt() != null) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, CANCELLED_MESSAGE);
        }
        if (task.getPublishedAt() != null) {
            // 重复发布：直接成功，不再生成名额 / 托管（幂等）
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Map<String, String> errors = validator.validate(toRequest(task), now, true);
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, INVALID_PARAMETER_MESSAGE,
                    Map.of("fields", errors));
        }

        int claimed = taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, taskId)
                .isNull(Task::getPublishedAt)
                .isNull(Task::getCancelledAt)
                .set(Task::getPublishedAt, now)
                .set(Task::getFeeRate, feeRate));
        if (claimed != 1) {
            Task latest = taskMapper.selectById(taskId);
            if (latest != null && latest.getPublishedAt() != null) {
                return;
            }
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, CONFLICT_MESSAGE);
        }

        List<EscrowService.SlotHold> holds = new ArrayList<>(task.getSlotCount());
        for (int seq = 1; seq <= task.getSlotCount(); seq++) {
            TaskSlot slot = new TaskSlot();
            slot.setTaskId(taskId);
            slot.setSeqNo(seq);
            slot.setStatus(SlotStatus.SLOT_AVAILABLE);
            taskSlotMapper.insert(slot);
            holds.add(new EscrowService.SlotHold(slot.getId(), task.getUnitPrice()));
        }

        escrowService.hold(taskId, current.userId(), feeRate, holds, String.valueOf(taskId));
        TaskCacheEviction.afterCommit(taskDetailCache, taskId);
    }

    private static TaskDraftRequest toRequest(Task task) {
        return new TaskDraftRequest(
                task.getTitle(),
                task.getDescription(),
                task.getCategory(),
                task.getUnitPrice(),
                task.getSlotCount(),
                task.getStartTime(),
                task.getEndTime(),
                task.getWorkLocation(),
                task.getSettlementNote());
    }
}
