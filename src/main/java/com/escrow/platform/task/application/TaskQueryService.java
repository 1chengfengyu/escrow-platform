package com.escrow.platform.task.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.fund.application.EscrowService;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.task.api.TaskDetailView;
import com.escrow.platform.task.api.TaskListItemView;
import com.escrow.platform.task.domain.OpenDisputeQuery;
import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.domain.TaskDetailVisibility;
import com.escrow.platform.task.domain.TaskDisplayStatus;
import com.escrow.platform.task.domain.TaskSearchCriteria;
import com.escrow.platform.task.domain.TaskSlot;
import com.escrow.platform.task.domain.TaskStatusAggregator;
import com.escrow.platform.task.domain.TaskStatusFacts;
import com.escrow.platform.task.infrastructure.cache.TaskDetailCache;
import com.escrow.platform.task.infrastructure.cache.TaskDetailSnapshot;
import com.escrow.platform.task.infrastructure.mapper.TaskListItemRow;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
import com.escrow.platform.task.infrastructure.mapper.TaskSlotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务检索与详情（FR-TASK.LIST.01~.04、FR-TASK.DETAIL.01~.02，docs/decisions.md #48）。
 *
 * <p>边界：<b>列表不做任何业务守卫</b>——列表不以聚合状态为条件（FR-TASK.LIST.03），
 * 角标沿用 SQL 行的标记字段。详情返回派生状态（R0~R12）供展示，
 * 但派生状态本身<b>不作为业务守卫</b>；发布 / 撤单 / 到期 / 报名由后续流程处理。</p>
 *
 * <p>跨域：{@code hasOpenDispute} 与详情里的 {@code openDisputeCount} 走
 * {@link OpenDisputeQuery} 端口（由 dispute 域实现，V7 起接入），不直查对方表。</p>
 *
 * @since V2
 */
@Service
@RequiredArgsConstructor
public class TaskQueryService {

    private static final String TASK_NOT_FOUND_MESSAGE = "任务不存在";

    private final TaskMapper taskMapper;
    private final TaskSlotMapper taskSlotMapper;
    private final TaskDetailCache taskDetailCache;
    /** 跨域只走 fund 域 Service 入口，禁止直查 fund Mapper（project_rules §3）。 */
    private final EscrowService escrowService;
    /** 未决争议计数端口，由 dispute 域实现（R2 争议中）。 */
    private final OpenDisputeQuery openDisputeQuery;

    /**
     * 列表：只做「查 / 转换 / 包 PageView」，不含任何业务守卫（FR-TASK.LIST.01~.04）。
     *
     * @param criteria 已校验的检索条件
     * @return 当前页视图；页码越界返回空 items + 真实 total
     */
    public PageView<TaskListItemView> list(TaskSearchCriteria criteria) {
        Page<TaskListItemRow> request = new Page<>(criteria.page(), criteria.size());
        Page<TaskListItemRow> result = taskMapper.selectTaskPage(request, criteria);
        List<TaskListItemView> items = result.getRecords().stream()
                .map(row -> new TaskListItemView(
                        row.getId(),
                        row.getTitle(),
                        row.getCategory(),
                        row.getUnitPrice(),
                        row.getWorkLocation(),
                        row.getStartTime(),
                        row.getEndTime(),
                        row.getPublishedAt(),
                        row.getRemainingSlots(),
                        Boolean.TRUE.equals(row.getHasOpenDispute()),
                        Boolean.TRUE.equals(row.getHasSettlementAbnormal())))
                .toList();
        return PageView.of(items, criteria.page(), criteria.size(), result.getTotal());
    }

    /**
     * 任务总数（V7 治理看板用，只读）。
     *
     * <p>口径是「表内全部任务」，含草稿与已撤单：看板展示的是平台存量，
     * 不是「招募中数量」；后者属于列表筛选的职责，不在这里另定一套口径。</p>
     *
     * @return {@code task} 行数
     */
    public long countAll() {
        Long count = taskMapper.selectCount(null);
        return count == null ? 0L : count;
    }

    /**
     * 详情：Caffeine → Redis → DB 三级缓存（FR-TASK.DETAIL.01~.02，v2-c4 / docs/decisions.md #49）。
     *
     * <p>缓存的是与观察者无关的 {@link TaskDetailSnapshot}；当事人判定与名额明细投影
     * 每次都按当前会话实时计算，因此同一缓存项对不同观察者不会串结果。</p>
     *
     * <p>顺序固定：取快照（不存在 404）→ 判定当事人 → 可见性（草稿对非本人 404，
     * 不暴露存在性）→ 投影。{@code escrowAmount} 取托管总额快照（v2-c6），仅当事人可见；
     * 未托管或非当事人为 {@code null}，由类上 {@code @JsonInclude(NON_NULL)} 让该键不出现。</p>
     *
     * @param current 会话主体；切片场景可能为 null
     * @param id      任务 ID
     * @return 详情视图；当事人带名额明细，非当事人不带
     * @throws ApiException 404 任务不存在或草稿对非雇主不可见
     */
    @Transactional(readOnly = true)
    public TaskDetailView detail(LoginUser current, Long id) {
        TaskDetailSnapshot snapshot = taskDetailCache.get(id, () -> loadSnapshot(id));
        if (snapshot == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, TASK_NOT_FOUND_MESSAGE);
        }

        Long viewerId = current == null ? null : current.userId();
        boolean party = TaskDetailVisibility.isParty(snapshot.employerId(), viewerId, false);
        if (!TaskDetailVisibility.isVisibleTo(snapshot.publishedAt(), party)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, TASK_NOT_FOUND_MESSAGE);
        }

        List<TaskDetailView.SlotItem> slotOccupancy = party
                ? snapshot.slots().stream()
                        .map(slot -> new TaskDetailView.SlotItem(slot.seqNo(), slot.status()))
                        .toList()
                : null;

        // 派生状态按快照实时计算、不缓存（D11）：同一快照对不同请求算出的状态只取决于名额与时间。
        // 只有存在待裁决名额时才需要问争议域；无争议的详情不产生额外查询。
        int openDisputeCount = snapshot.pendingArbitration() > 0 ? countOpenDisputes(id) : 0;
        TaskStatusFacts facts = factsOf(snapshot, openDisputeCount);

        return new TaskDetailView(
                snapshot.id(),
                snapshot.title(),
                snapshot.description(),
                snapshot.category(),
                snapshot.unitPrice(),
                snapshot.slotCount(),
                snapshot.startTime(),
                snapshot.endTime(),
                snapshot.workLocation(),
                snapshot.settlementNote(),
                snapshot.publishedAt(),
                snapshot.cancelledAt(),
                snapshot.available(),
                slotOccupancy,
                // 托管金额是当事人专属字段（FR-TASK.DETAIL.01 / NFR-SEC.03）：
                // 非当事人置 null，靠类上 @JsonInclude(NON_NULL) 让该键完全不出现
                party ? snapshot.escrowAmount() : null,
                party,
                false,
                snapshot.pendingArbitration() > 0,
                TaskStatusAggregator.aggregate(facts),
                facts.effectiveRecruitingClosed());
    }

    /**
     * 由快照统计九个名额计数并组装聚合器输入（FR-TASK.DETAIL.02，SRS A.3.2）。
     *
     * <p>{@code openDisputeCount} 由 {@link OpenDisputeQuery} 实时统计（V7 起接入，
     * 此前恒 0 导致 R2 争议中永远走不到）；{@code effectiveRecruitingClosed}
     * = 撤单 OR 到期，按请求时刻实时判定，因此同一快照跨过 {@code endTime} 后会自动改判为已关闭招募。</p>
     *
     * @param snapshot         与观察者无关的详情快照
     * @param openDisputeCount 该任务名额上未决的争议数
     * @return 聚合器输入事实
     */
    private static TaskStatusFacts factsOf(TaskDetailSnapshot snapshot, int openDisputeCount) {
        int available = 0;
        int occupied = 0;
        int inProgress = 0;
        int delivered = 0;
        int pendingArbitration = 0;
        int pendingSettlement = 0;
        int completed = 0;
        int refunded = 0;
        int released = 0;
        for (TaskDetailSnapshot.SlotSnapshot slot : snapshot.slots()) {
            switch (slot.status()) {
                case SLOT_AVAILABLE -> available++;
                case SLOT_OCCUPIED -> occupied++;
                case SLOT_IN_PROGRESS -> inProgress++;
                case SLOT_DELIVERED -> delivered++;
                case SLOT_PENDING_ARBITRATION -> pendingArbitration++;
                case SLOT_PENDING_SETTLEMENT -> pendingSettlement++;
                case SLOT_COMPLETED -> completed++;
                case SLOT_REFUNDED -> refunded++;
                case SLOT_RELEASED -> released++;
            }
        }
        boolean cancelled = snapshot.cancelledAt() != null;
        boolean recruitingClosed = cancelled
                || !LocalDateTime.now().isBefore(snapshot.endTime());
        return new TaskStatusFacts(
                snapshot.publishedAt() == null,
                cancelled,
                recruitingClosed,
                available,
                occupied,
                inProgress,
                delivered,
                pendingArbitration,
                pendingSettlement,
                completed,
                refunded,
                released,
                openDisputeCount);
    }

    /**
     * 统计任务名下所有名额的未决争议数（R2 争议中）。
     *
     * <p>先在本域按 {@code task_id} 取名额 ID（单表查询，不跨域 join），
     * 再交给 dispute 域的实现计数；名额为空时不查争议表。</p>
     *
     * @param taskId 任务 ID
     * @return 未决争议数
     */
    private int countOpenDisputes(Long taskId) {
        List<TaskSlot> slots = taskSlotMapper.selectList(new LambdaQueryWrapper<TaskSlot>()
                .select(TaskSlot::getId)
                .eq(TaskSlot::getTaskId, taskId));
        if (slots.isEmpty()) {
            return 0;
        }
        return openDisputeQuery.countOpen(slots.stream().map(TaskSlot::getId).toList());
    }

    /**
     * 读取任务发布者（验收归属校验的跨域只读入口）。
     *
     * <p>engagement 域不得直查 {@code task} 表；验收需要判断「当前登录人是否该任务的雇主」，
     * 只能走本方法。任务不存在按 404 处理，不暴露内部 ID。</p>
     *
     * @param taskId 任务 ID
     * @return 雇主用户 ID
     * @throws ApiException 404 任务不存在
     */
    public Long employerIdOf(Long taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, TASK_NOT_FOUND_MESSAGE);
        }
        return task.getEmployerId();
    }

    /**
     * 会话详情用的任务摘要（V11，FR-MSG.CHAT.01，补遗 §4）。
     *
     * <p>chat 域不得直查 {@code task} 表，会话里的任务标题只能走本入口。只给展示所需的
     * 最小字段，托管金额、雇主余额等资金字段一律不带出（FR-TASK.DETAIL.01 字段裁剪）。</p>
     *
     * @param taskId 任务 ID
     * @return 任务摘要
     * @throws ApiException 404 任务不存在
     */
    public TaskBrief briefOf(Long taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, TASK_NOT_FOUND_MESSAGE);
        }
        return new TaskBrief(task.getId(), task.getTitle(), task.getEmployerId(), task.getPublishedAt());
    }

    /**
     * 任务摘要（FR-MSG.CHAT.01，补遗 §4）。
     *
     * @param taskId      任务 ID
     * @param title       任务标题
     * @param employerId  雇主账号 ID
     * @param publishedAt 发布时间；{@code null} = 草稿
     */
    public record TaskBrief(Long taskId, String title, Long employerId, LocalDateTime publishedAt) {
    }

    /**
     * 详情回源：任务不存在返回 {@code null}，交由缓存层落空值标记。
     *
     * @param id 任务 ID
     * @return 与观察者无关的快照；不存在为 null
     */
    private TaskDetailSnapshot loadSnapshot(Long id) {
        Task task = taskMapper.selectById(id);
        if (task == null) {
            return null;
        }
        List<TaskSlot> slots = taskSlotMapper.selectList(new LambdaQueryWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, id)
                .orderByAsc(TaskSlot::getSeqNo));
        // 只在 miss 重建快照时查一次 fund 域；命中路径不碰 fund（v2-c6 §5 第 2 条）
        return TaskDetailSnapshot.from(task, slots, escrowService.totalAmountOf(id));
    }
}
