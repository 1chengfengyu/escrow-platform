package com.escrow.platform.task.domain;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 任务检索条件（FR-TASK.LIST.01~.04，docs/decisions.md #48）。
 *
 * <p><b>唯一入口是 {@link #from} 静态工厂</b>：Controller 一律用 {@code String} 收参后交给它，
 * 不用 {@code int page} / {@code LocalDateTime from} 直接做 {@code @RequestParam} 绑定——
 * 类型转换失败会抛 {@code MethodArgumentTypeMismatchException}，而
 * {@code GlobalExceptionHandler} 没有这个分支，会落到 {@code Exception} → 500。</p>
 *
 * <p>任何一条不满足都抛 {@code INVALID_PARAMETER}，{@code data.fields} 指明字段与原因，
 * 前端可以高亮到具体输入框。</p>
 *
 * @param keyword  关键词，匹配标题或描述；null 表示不筛
 * @param category 类别，等值匹配；null 表示不筛
 * @param minPrice 单价下限（分），含端点；null 表示不筛
 * @param maxPrice 单价上限（分），含端点；null 表示不筛
 * @param from     时间范围下限，与任务时间窗取交集；null 表示不筛
 * @param to       时间范围上限，与任务时间窗取交集；null 表示不筛
 * @param location 工作地点，模糊匹配；null 表示不筛
 * @param page     页码，从 1 开始
 * @param size     每页条数，1~100
 * @param sort     排序字段白名单
 * @param order    排序方向
 * @since V2
 */
public record TaskSearchCriteria(
        String keyword, String category, Long minPrice, Long maxPrice,
        LocalDateTime from, LocalDateTime to, String location,
        int page, int size, TaskSortField sort, SortDirection order) {

    private static final String INVALID_MESSAGE = "请求参数不合法";
    private static final int KEYWORD_MAX_LENGTH = 50;
    private static final int CATEGORY_MAX_LENGTH = 32;
    private static final int LOCATION_MAX_LENGTH = 128;
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    /**
     * 解析并校验全部入参（顺序即实现顺序）。
     *
     * @throws ApiException 任一参数不合法，{@code code=INVALID_PARAMETER}，{@code data.fields} 指明字段
     */
    public static TaskSearchCriteria from(String keyword, String category, String minPrice, String maxPrice,
                                          String from, String to, String location,
                                          String page, String size, String sort, String order) {
        String parsedKeyword = trimToNull(keyword);
        if (parsedKeyword != null && parsedKeyword.length() > KEYWORD_MAX_LENGTH) {
            throw invalid("keyword", "关键词长度不能超过 " + KEYWORD_MAX_LENGTH + " 字");
        }
        String parsedCategory = trimToNull(category);
        if (parsedCategory != null && parsedCategory.length() > CATEGORY_MAX_LENGTH) {
            throw invalid("category", "类别长度不能超过 " + CATEGORY_MAX_LENGTH + " 字");
        }
        String parsedLocation = trimToNull(location);
        if (parsedLocation != null && parsedLocation.length() > LOCATION_MAX_LENGTH) {
            throw invalid("location", "工作地点长度不能超过 " + LOCATION_MAX_LENGTH + " 字");
        }

        Long parsedMinPrice = parsePrice(minPrice, "minPrice");
        Long parsedMaxPrice = parsePrice(maxPrice, "maxPrice");
        if (parsedMinPrice != null && parsedMaxPrice != null && parsedMinPrice > parsedMaxPrice) {
            throw invalid("minPrice", "minPrice 不能大于 maxPrice");
        }

        LocalDateTime parsedFrom = parseDateTime(from, "from");
        LocalDateTime parsedTo = parseDateTime(to, "to");
        if (parsedFrom != null && parsedTo != null && parsedFrom.isAfter(parsedTo)) {
            throw invalid("from", "from 不能晚于 to");
        }

        return new TaskSearchCriteria(
                parsedKeyword, parsedCategory, parsedMinPrice, parsedMaxPrice,
                parsedFrom, parsedTo, parsedLocation,
                parsePage(page), parseSize(size), parseSort(sort), parseOrder(order));
    }

    /** trim 后空串视为「不筛」。 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 单价：单位分、必须 ≥1；非数字与 0 / 负数都拒绝。 */
    private static Long parsePrice(String value, String field) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        long parsed;
        try {
            parsed = Long.parseLong(text);
        } catch (NumberFormatException ex) {
            throw invalid(field, "必须是整数");
        }
        if (parsed <= 0) {
            throw invalid(field, "必须大于 0");
        }
        return parsed;
    }

    /** 时间：ISO-8601 {@code LocalDateTime}，如 {@code 2026-12-01T09:00:00}。 */
    private static LocalDateTime parseDateTime(String value, String field) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ex) {
            throw invalid(field, "必须是 ISO 日期时间，如 2026-12-01T09:00:00");
        }
    }

    private static int parsePage(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return DEFAULT_PAGE;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            throw invalid("page", "必须是整数");
        }
        if (parsed < 1) {
            throw invalid("page", "不能小于 1");
        }
        return parsed;
    }

    private static int parseSize(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return DEFAULT_SIZE;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            throw invalid("size", "必须是整数");
        }
        if (parsed < 1 || parsed > MAX_SIZE) {
            throw invalid("size", "必须在 1~" + MAX_SIZE + " 之间");
        }
        return parsed;
    }

    private static TaskSortField parseSort(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return TaskSortField.PUBLISHED_AT;
        }
        try {
            return TaskSortField.fromWire(text);
        } catch (IllegalArgumentException ex) {
            throw invalid("sort", "只允许 published_at 或 unit_price");
        }
    }

    private static SortDirection parseOrder(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return SortDirection.DESC;
        }
        try {
            return SortDirection.fromWire(text);
        } catch (IllegalArgumentException ex) {
            throw invalid("order", "只允许 asc 或 desc");
        }
    }

    private static ApiException invalid(String field, String reason) {
        return new ApiException(ErrorCode.INVALID_PARAMETER, INVALID_MESSAGE,
                Map.of("fields", Map.of(field, reason)));
    }
}
