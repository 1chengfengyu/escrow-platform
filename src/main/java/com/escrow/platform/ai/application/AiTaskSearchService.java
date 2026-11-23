package com.escrow.platform.ai.application;

import com.escrow.platform.ai.domain.AiPhase;
import com.escrow.platform.ai.domain.AiRankStrategy;
import com.escrow.platform.ai.domain.QuerySlots;
import com.escrow.platform.ai.domain.TraceStage;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.task.api.TaskListItemView;
import com.escrow.platform.task.application.TaskQueryService;
import com.escrow.platform.task.domain.SortDirection;
import com.escrow.platform.task.domain.TaskSearchCriteria;
import com.escrow.platform.task.domain.TaskSortField;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 劳动者找活（V12.1）：把雇主语义的草稿槽位反转成找活条件，只读检索任务。
 *
 * <p><b>语义反转</b>：{@code priceMax} 对雇主是预算上限，对劳动者是「报酬至少」，
 * 因此映射为 {@code unitPrice} 下限；{@code headcount} 在找活里没有对应字段，直接丢弃；
 * {@code weekend} 从「要求对方周末可接」变为「任务时间窗覆盖周末」，在结果上过滤。</p>
 *
 * <p><b>不接模型</b>：确定性打分 + 模板理由，理由只引用任务行里真实存在的字段，
 * 保证每条都能核。链路仍写入 {@code ai_trace}，便于用同一个 traceNo 回放。</p>
 *
 * @since V12.1
 */
@Service
@RequiredArgsConstructor
public class AiTaskSearchService {

    /** 召回上限：周末过滤后再截断，避免一次拉全表。 */
    private static final int RECALL_SIZE = 50;
    /** 返回上限。 */
    private static final int RESULT_SIZE = 20;
    private static final int WEEKEND_SCAN_DAYS = 30;
    private static final String EXPIRED_MESSAGE = "解析结果已过期，请重新解析需求";

    private final AiDraftStore draftStore;
    private final TaskQueryService taskQueryService;
    private final AiTraceService traceService;

    /**
     * 按草稿检索可接的任务。
     *
     * @param current     会话主体（仅劳动者，门禁在 Controller）
     * @param draftId     parse 产出的草稿ID
     * @param removedKeys 用户在标签上删掉的槽位键
     * @return 按确定性分数倒序的任务列表；空结果带放宽提示
     * @throws ApiException 草稿不存在或已过期（404）
     */
    public AiResults.TaskSearch search(LoginUser current, String draftId, List<String> removedKeys) {
        AiDraftStore.Draft draft = draftId == null || draftId.isBlank()
                ? null
                : draftStore.find(draftId).orElse(null);
        if (draft == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, EXPIRED_MESSAGE);
        }
        QuerySlots slots = draft.applyRemovals(removedKeys);
        boolean weekendOnly = Boolean.TRUE.equals(slots.weekend());
        String keyword = resolveKeyword(slots);
        List<TraceStage> stages = new ArrayList<>();

        long start = System.nanoTime();
        TaskSearchCriteria criteria = new TaskSearchCriteria(
                keyword, null, slots.priceMax(), null, null, null, slots.location(),
                1, RECALL_SIZE, TaskSortField.PUBLISHED_AT, SortDirection.DESC);
        PageView<TaskListItemView> page = taskQueryService.list(criteria);
        List<TaskListItemView> recalled = page.items();
        stages.add(TraceStage.plain(AiPhase.FILTER, msSince(start),
                "按报价下限与地点召回 " + recalled.size() + " 条"));

        start = System.nanoTime();
        List<TaskListItemView> filtered = weekendOnly
                ? recalled.stream().filter(this::weekendFits).toList()
                : recalled;
        stages.add(TraceStage.plain(AiPhase.RECALL, msSince(start),
                "周末时间窗过滤后 " + filtered.size() + " 条"));

        start = System.nanoTime();
        List<AiResults.TaskSearchItem> items = filtered.stream()
                .sorted(Comparator
                        .comparingInt((TaskListItemView row) -> score(row, slots, keyword, weekendOnly)).reversed()
                        .thenComparing(TaskListItemView::publishedAt,
                                Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder())))
                .limit(RESULT_SIZE)
                .map(row -> toItem(row, slots, keyword, weekendOnly))
                .toList();
        stages.add(TraceStage.plain(AiPhase.GENERATE, msSince(start), "模板理由 " + items.size() + " 条"));

        String traceNo = traceService.record(current.userId(), draft.queryText(),
                AiRankStrategy.WEIGHTED, 0, items.size(), stages);
        return new AiResults.TaskSearch(items, AiRankStrategy.WEIGHTED.name(), 0, traceNo, note(items.size(), weekendOnly));
    }

    /** 技能优先，其次第一个关键词；两者都没有就不加关键词条件。 */
    private String resolveKeyword(QuerySlots slots) {
        if (slots.skill() != null && !slots.skill().isBlank()) {
            return slots.skill().trim();
        }
        return slots.keywords().stream()
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .findFirst()
                .map(String::trim)
                .orElse(null);
    }

    /** 确定性打分：命中项可逐条解释，不做模型重排。 */
    private int score(TaskListItemView row, QuerySlots slots, String keyword, boolean weekendOnly) {
        int score = 10;
        if (keyword != null) {
            if (contains(row.title(), keyword)) {
                score += 35;
            } else if (contains(row.category(), keyword)) {
                score += 20;
            }
        }
        if (slots.location() != null && contains(row.workLocation(), slots.location())) {
            score += 20;
        }
        if (slots.priceMax() != null) {
            score += 20;
        }
        if (weekendOnly && weekendFits(row)) {
            score += 10;
        }
        if (row.remainingSlots() > 0) {
            score += 10;
        }
        if (row.publishedAt() != null && row.publishedAt().isAfter(LocalDateTime.now().minusDays(7))) {
            score += 5;
        }
        return Math.min(score, 100);
    }

    private AiResults.TaskSearchItem toItem(TaskListItemView row, QuerySlots slots,
                                            String keyword, boolean weekendOnly) {
        Set<String> evidence = new LinkedHashSet<>();
        List<String> parts = new ArrayList<>();
        parts.add("报酬 " + yuan(row.unitPrice()) + " 元/名额");
        evidence.add("unitPrice");
        if (slots.priceMax() != null) {
            parts.add("不低于你的期望下限 " + yuan(slots.priceMax()) + " 元");
        }
        if (row.workLocation() != null && !row.workLocation().isBlank()) {
            parts.add("地点 " + row.workLocation());
            evidence.add("workLocation");
        }
        if (weekendOnly) {
            parts.add("时间窗覆盖周末");
            evidence.add("startTime");
            evidence.add("endTime");
        }
        if (row.remainingSlots() > 0) {
            parts.add("剩 " + row.remainingSlots() + " 个名额");
            evidence.add("remainingSlots");
        }
        if (row.publishedAt() != null) {
            parts.add("发布于 " + row.publishedAt().toLocalDate());
            evidence.add("publishedAt");
        }
        if (keyword != null && contains(row.title(), keyword)) {
            evidence.add("title");
        }
        return new AiResults.TaskSearchItem(row.id(), row.title(), row.category(), row.unitPrice(),
                row.workLocation(), row.startTime(), row.endTime(), row.remainingSlots(),
                score(row, slots, keyword, weekendOnly), String.join("；", parts), List.copyOf(evidence));
    }

    /** 周末判断：任务时间窗覆盖周六或周日；时间缺失时不排除。 */
    private boolean weekendFits(TaskListItemView row) {
        LocalDateTime start = row.startTime();
        LocalDateTime end = row.endTime();
        if (start == null || end == null) {
            return true;
        }
        LocalDate cursor = start.toLocalDate();
        LocalDate last = end.toLocalDate();
        for (int day = 0; day < WEEKEND_SCAN_DAYS && !cursor.isAfter(last); day++) {
            DayOfWeek weekday = cursor.getDayOfWeek();
            if (weekday == DayOfWeek.SATURDAY || weekday == DayOfWeek.SUNDAY) {
                return true;
            }
            cursor = cursor.plusDays(1);
        }
        return false;
    }

    private boolean contains(String source, String keyword) {
        return source != null && source.contains(keyword);
    }

    private String note(int size, boolean weekendOnly) {
        if (size > 0) {
            return null;
        }
        return weekendOnly
                ? "没有同时满足报价、地点与周末时间的任务，可改用「不限周末」再试。"
                : "没有满足报价与地点的任务，可放宽报价或换一个地点。";
    }

    /** 分转元，仅用于展示，不参与金额运算。 */
    private String yuan(long fen) {
        return (fen / 100) + "." + String.format("%02d", fen % 100);
    }

    private long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
