package com.escrow.platform.ai.application;

import com.escrow.platform.ai.domain.QuerySlots;
import com.escrow.platform.ai.domain.SlotTag;
import com.escrow.platform.ai.domain.TraceStage;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 应用层返回体（V10）：api 层据此组装 {@code ApiResponse}，application 不反向依赖 api 的 DTO。
 *
 * <p>全部是 {@code record}：不可变、无 setter，避免被上层改动后又被下游读到半截状态。</p>
 *
 * @since V10
 */
public final class AiResults {

    private AiResults() {
    }

    /**
     * 查询理解结果。
     *
     * @param draftId  草稿ID，5 分钟内有效；确认检索时回传
     * @param slots    结构化槽位
     * @param tags     回显给用户的可编辑标签
     * @param degraded 是否降级到规则解析
     * @param promptVersion prompt 版本号
     */
    public record Understand(String draftId, QuerySlots slots, List<SlotTag> tags,
                             boolean degraded, String promptVersion) {
    }

    /**
     * 单个候选人。
     *
     * @param reason         模板理由（全部字段可核）
     * @param evidenceFields 理由引用的画像字段名
     */
    public record SearchItem(long userId, int score, List<String> skills, long expectedUnitPrice,
                             String location, boolean weekendAvailable, int ratingX100, int completedOrders,
                             String reason, List<String> evidenceFields) {

        public SearchItem {
            skills = List.copyOf(skills);
            evidenceFields = List.copyOf(evidenceFields);
        }
    }

    /** 检索结果：带灰度策略与实际生效情况，便于在线对照。 */
    public record Search(List<SearchItem> items, String strategy, int bucket, String traceNo,
                         boolean rerankApplied, boolean rerankRejected, String rerankNote) {

        public Search {
            items = List.copyOf(items);
        }
    }

    /** RAG 答案：{@code abstain=true} 表示知识库无依据，不是幻觉。 */
    public record Qa(String answer, List<String> citations, boolean abstain, boolean degraded, String traceNo) {

        public Qa {
            citations = List.copyOf(citations);
        }
    }

    /** trace 回放视图。 */
    public record Trace(String traceNo, long userId, String queryText, String strategy, int bucket,
                        int resultCount, int totalTokens, long costMicros, List<TraceStage> stages,
                        LocalDateTime createdAt) {

        public Trace {
            stages = List.copyOf(stages);
        }
    }

    /** 画像视图：{@code sources} 区分真实事实与测试语料。 */
    public record Profile(long userId, List<String> skills, Integer experienceYears, Long expectedUnitPrice,
                          String location, Boolean weekendAvailable, Integer completedOrders, Integer ratingX100,
                          Map<String, String> sources, int profileVersion) {

        public Profile {
            skills = List.copyOf(skills);
            sources = Map.copyOf(sources);
        }
    }

    /** 画像更新命令（只含用户可填字段；完单数与评分由业务派生，不接受用户改）。 */
    public record ProfileCommand(List<String> skills, Integer experienceYears, Long expectedUnitPrice,
                                 String location, Boolean weekendAvailable) {
    }

    /**
     * 劳动者找活的单条任务。
     *
     * @param reason         模板理由（引用的字段值全部来自任务行）
     * @param evidenceFields 理由引用的任务字段名
     */
    public record TaskSearchItem(long taskId, String title, String category, long unitPrice,
                                 String workLocation, LocalDateTime startTime, LocalDateTime endTime,
                                 int remainingSlots, int score, String reason, List<String> evidenceFields) {

        public TaskSearchItem {
            evidenceFields = List.copyOf(evidenceFields);
        }
    }

    /**
     * 劳动者找活结果：与雇主找人共用 trace 与灰度口径。
     *
     * @param note 结果为空时的放宽提示
     */
    public record TaskSearch(List<TaskSearchItem> items, String strategy, int bucket, String traceNo,
                             String note) {

        public TaskSearch {
            items = List.copyOf(items);
        }
    }
}
