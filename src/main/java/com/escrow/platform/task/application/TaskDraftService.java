package com.escrow.platform.task.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.task.api.TaskDraftRequest;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.domain.TaskEditPolicy;
import com.escrow.platform.task.infrastructure.cache.TaskDetailCache;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 任务草稿的创建与编辑（FR-TASK.CREATE.01 / .02 / .03 / .05）。
 *
 * <p>边界（docs/decisions.md #47）：本类只做草稿，<b>不校验资质</b>（FR-ACCT.QUAL.03 只约束发布，
 * 归 v2-c5）；不实现发布 / 撤单 / 到期（资金动作，归 v2-c5）；草稿阶段 {@code task_slot} 零行。</p>
 *
 * <p>不做乐观锁、不做 {@code @Version}：草稿编辑并发为最后写入胜出（卡片 §11 风险 4）。
 * 更新必须用 {@code LambdaUpdateWrapper} 逐字段 {@code .set(...)}——
 * {@code updateById} 忽略 null，{@code settlementNote} 清不掉（V1 资质已踩过）；
 * 且严禁写 {@code publishedAt / cancelledAt / recruitingClosed / feeRate / employerId}。</p>
 *
 * @since V2
 */
@Service
@RequiredArgsConstructor
public class TaskDraftService {

    private static final String NOT_EMPLOYER_MESSAGE = "仅雇主身份可创建任务";
    private static final String INVALID_PARAMETER_MESSAGE = "请求参数不合法";
    private static final String TASK_NOT_FOUND_MESSAGE = "任务不存在";
    private static final String NOT_OWNER_MESSAGE = "无权修改他人任务";
    private static final String PRICE_LOCKED_MESSAGE = "任务已发布，单价与名额数不可修改";
    private static final String CANCELLED_MESSAGE = "任务已撤销，不可编辑";

    private final TaskMapper taskMapper;
    private final TaskDetailCache taskDetailCache;

    /** 无状态校验器，直接持有实例；草稿创建与编辑共用，v2-c5 发布时复用。 */
    private final TaskValidator validator = new TaskValidator();

    /**
     * 创建任务草稿（FR-TASK.CREATE.01~.03）。
     *
     * @param current 会话主体
     * @param req     草稿请求
     * @return 草稿任务 ID（雪花）
     * @throws ApiException 403 非雇主身份 / 400 字段不合法（{@code data.fields}）
     */
    @Transactional
    public Long create(LoginUser current, TaskDraftRequest req) {
        // FR-ACCT.QUAL.03 只约束发布，创建草稿不校验资质（docs/decisions.md #47「资质门禁」）
        if (current == null || current.currentIdentity() != IdentityType.EMPLOYER) {
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_EMPLOYER_MESSAGE);
        }

        Map<String, String> errors = validator.validate(req, LocalDateTime.now(), true);
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, INVALID_PARAMETER_MESSAGE,
                    Map.of("fields", errors));
        }

        Task task = new Task();
        task.setEmployerId(current.userId());
        copyEditableFields(req, task);
        // 派生口径的初始事实：草稿 = published_at 为空；显式置空，不依赖 MyBatis 默认行为
        task.setPublishedAt(null);
        task.setCancelledAt(null);
        task.setRecruitingClosed(false);
        task.setFeeRate(null);
        taskMapper.insert(task);
        return task.getId();
    }

    /**
     * 全量编辑任务草稿（FR-TASK.CREATE.02 / .05）。
     *
     * <p>价格锁先于字段校验：价格锁不是参数错，必须是 409 而不是 400（卡片 §6.2 第 4 步）。</p>
     *
     * @param current 会话主体
     * @param id      任务 ID
     * @param req     草稿请求（全量替换语义，不是 PATCH）
     * @throws ApiException 404 不存在 / 403 非雇主或非本人 / 409 已撤销或价格锁 / 400 字段不合法
     */
    @Transactional
    public void update(LoginUser current, Long id, TaskDraftRequest req) {
        if (current == null || current.currentIdentity() != IdentityType.EMPLOYER) {
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_EMPLOYER_MESSAGE);
        }

        Task existing = taskMapper.selectById(id);
        if (existing == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, TASK_NOT_FOUND_MESSAGE);
        }
        if (!existing.getEmployerId().equals(current.userId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_OWNER_MESSAGE);
        }
        // 撤单是终态：已撤销任务不许再编辑（否则会出现「已撤单任务字段仍可改」的口径）
        if (existing.getCancelledAt() != null) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, CANCELLED_MESSAGE);
        }

        boolean published = existing.getPublishedAt() != null;
        if (TaskEditPolicy.priceOrSlotChanged(published,
                existing.getUnitPrice(), existing.getSlotCount(),
                req.unitPrice(), req.slotCount())) {
            throw new ApiException(ErrorCode.TASK_PRICE_LOCKED, PRICE_LOCKED_MESSAGE);
        }

        Map<String, String> errors = validator.validate(req, LocalDateTime.now(),
                TaskEditPolicy.requireFutureStart(published));
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, INVALID_PARAMETER_MESSAGE,
                    Map.of("fields", errors));
        }

        // 逐字段显式 set：settlementNote 传 null 时必须能清掉（updateById 忽略 null）；
        // 严禁 set 派生列与归属列（published_at / cancelled_at / recruiting_closed / fee_rate / employer_id）
        taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, id)
                .set(Task::getTitle, req.title())
                .set(Task::getDescription, req.description())
                .set(Task::getCategory, req.category())
                .set(Task::getUnitPrice, req.unitPrice())
                .set(Task::getSlotCount, req.slotCount())
                .set(Task::getStartTime, req.startTime())
                .set(Task::getEndTime, req.endTime())
                .set(Task::getWorkLocation, req.workLocation())
                .set(Task::getSettlementNote, req.settlementNote()));

        // Cache-Aside：先提交 DB，事务提交后再删缓存（v2-c4，docs/decisions.md #49）。
        // 与发布 / 撤单 / 到期共用同一段逻辑，避免某一处漏删或改成「先删后更」。
        TaskCacheEviction.afterCommit(taskDetailCache, id);
    }

    private static void copyEditableFields(TaskDraftRequest req, Task task) {
        task.setTitle(req.title());
        task.setDescription(req.description());
        task.setCategory(req.category());
        task.setUnitPrice(req.unitPrice());
        task.setSlotCount(req.slotCount());
        task.setStartTime(req.startTime());
        task.setEndTime(req.endTime());
        task.setWorkLocation(req.workLocation());
        task.setSettlementNote(req.settlementNote());
    }
}
