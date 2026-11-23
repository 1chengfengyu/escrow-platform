package com.escrow.platform.task.application;

import com.escrow.platform.task.api.TaskDraftRequest;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务草稿字段校验（FR-TASK.CREATE.02），纯 Java、无 Spring 依赖，可直接单测。
 *
 * <p>与 {@code RegisterValidator} / {@code QualificationValidator} 同一手法的理由：
 * {@code @Valid} 抛的 {@code BindException} 会被全局处理器兜成 {@code VALIDATION_FAILED}；
 * 而任务口的字段错误要求 {@code INVALID_PARAMETER} 且 {@code data.fields} 指明字段
 * （docs/decisions.md #30）。</p>
 *
 * <p>长度按<b>字符数</b>（{@code String.length()} 对 BMP 即字符数），与 DDL 的
 * {@code CHAR_LENGTH} 一致；一个字段只留一条错误（先命中先返回），与 V1 一致。
 * 空判定在 trim 后做，与 V1 校验器一致；长度检查在原值上做。</p>
 *
 * <p>v2-c5 发布时复用同一校验器（docs/decisions.md #47「校验器复用」）。</p>
 *
 * @since V2
 */
public class TaskValidator {

    private static final int TITLE_MAX_LENGTH = 60;
    private static final int DESCRIPTION_MAX_LENGTH = 2000;
    private static final int CATEGORY_MAX_LENGTH = 32;
    private static final int WORK_LOCATION_MAX_LENGTH = 128;
    private static final int SETTLEMENT_NOTE_MAX_LENGTH = 500;

    private static final String TITLE_REQUIRED_MESSAGE = "标题不能为空";
    private static final String TITLE_TOO_LONG_MESSAGE = "标题长度不能超过 60";
    private static final String DESCRIPTION_REQUIRED_MESSAGE = "任务描述不能为空";
    private static final String DESCRIPTION_TOO_LONG_MESSAGE = "任务描述长度不能超过 2000";
    private static final String CATEGORY_REQUIRED_MESSAGE = "任务类别不能为空";
    private static final String CATEGORY_TOO_LONG_MESSAGE = "任务类别长度不能超过 32";
    private static final String UNIT_PRICE_REQUIRED_MESSAGE = "单价不能为空";
    private static final String UNIT_PRICE_POSITIVE_MESSAGE = "单价必须大于 0";
    private static final String SLOT_COUNT_REQUIRED_MESSAGE = "名额数不能为空";
    private static final String SLOT_COUNT_RANGE_MESSAGE = "名额数必须在 1~20 之间";
    private static final String START_TIME_REQUIRED_MESSAGE = "开始时间不能为空";
    private static final String END_TIME_REQUIRED_MESSAGE = "结束时间不能为空";
    private static final String TIME_RANGE_MESSAGE = "结束时间必须晚于开始时间";
    private static final String START_TIME_PAST_MESSAGE = "开始时间不得早于当前时间";
    private static final String WORK_LOCATION_REQUIRED_MESSAGE = "工作地点不能为空";
    private static final String WORK_LOCATION_TOO_LONG_MESSAGE = "工作地点长度不能超过 128";
    private static final String SETTLEMENT_NOTE_TOO_LONG_MESSAGE = "结算说明长度不能超过 500";

    /**
     * 校验草稿字段。
     *
     * @param req                草稿请求
     * @param now                当前时间（由调用方传入，本类不读时钟）
     * @param requireFutureStart 是否要求开始时间不早于当前时间：草稿 true；
     *                           已发布任务编辑 false（FR-TASK.CREATE.05，卡片 §1.3 第 9 条）
     * @return 字段名 → 错误文案；空 Map 表示全部通过
     */
    public Map<String, String> validate(TaskDraftRequest req, LocalDateTime now, boolean requireFutureStart) {
        Map<String, String> fields = new LinkedHashMap<>();

        String title = req.title() == null ? "" : req.title().trim();
        if (title.isEmpty()) {
            fields.put("title", TITLE_REQUIRED_MESSAGE);
        } else if (req.title().length() > TITLE_MAX_LENGTH) {
            fields.put("title", TITLE_TOO_LONG_MESSAGE);
        }

        String description = req.description() == null ? "" : req.description().trim();
        if (description.isEmpty()) {
            fields.put("description", DESCRIPTION_REQUIRED_MESSAGE);
        } else if (req.description().length() > DESCRIPTION_MAX_LENGTH) {
            fields.put("description", DESCRIPTION_TOO_LONG_MESSAGE);
        }

        String category = req.category() == null ? "" : req.category().trim();
        if (category.isEmpty()) {
            fields.put("category", CATEGORY_REQUIRED_MESSAGE);
        } else if (req.category().length() > CATEGORY_MAX_LENGTH) {
            fields.put("category", CATEGORY_TOO_LONG_MESSAGE);
        }

        if (req.unitPrice() == null) {
            fields.put("unitPrice", UNIT_PRICE_REQUIRED_MESSAGE);
        } else if (req.unitPrice() <= 0) {
            fields.put("unitPrice", UNIT_PRICE_POSITIVE_MESSAGE);
        }

        if (req.slotCount() == null) {
            fields.put("slotCount", SLOT_COUNT_REQUIRED_MESSAGE);
        } else if (req.slotCount() < 1 || req.slotCount() > 20) {
            fields.put("slotCount", SLOT_COUNT_RANGE_MESSAGE);
        }

        if (req.startTime() == null) {
            fields.put("startTime", START_TIME_REQUIRED_MESSAGE);
        }
        if (req.endTime() == null) {
            fields.put("endTime", END_TIME_REQUIRED_MESSAGE);
        }
        if (req.startTime() != null && req.endTime() != null) {
            if (!req.endTime().isAfter(req.startTime())) {
                // 相等也算错（FR-TASK.CREATE.02「结束时间晚于开始时间」）
                fields.put("endTime", TIME_RANGE_MESSAGE);
            } else if (requireFutureStart && req.startTime().isBefore(now)) {
                // 等于 now 允许，用 isBefore 判定（卡片 §6.1）
                fields.put("startTime", START_TIME_PAST_MESSAGE);
            }
        }

        String workLocation = req.workLocation() == null ? "" : req.workLocation().trim();
        if (workLocation.isEmpty()) {
            fields.put("workLocation", WORK_LOCATION_REQUIRED_MESSAGE);
        } else if (req.workLocation().length() > WORK_LOCATION_MAX_LENGTH) {
            fields.put("workLocation", WORK_LOCATION_TOO_LONG_MESSAGE);
        }

        if (req.settlementNote() != null && req.settlementNote().length() > SETTLEMENT_NOTE_MAX_LENGTH) {
            fields.put("settlementNote", SETTLEMENT_NOTE_TOO_LONG_MESSAGE);
        }

        return fields;
    }
}
