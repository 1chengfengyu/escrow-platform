package com.escrow.platform.common.web;

import java.util.List;

/**
 * 分页视图（FR-TASK.LIST.02，docs/decisions.md #48）。
 *
 * <p>{@code total} 是 {@code long}，经 {@code JacksonConfig} 全局序列化成字符串
 * （docs/decisions.md #16 的既有契约），不要为了「看起来是数字」改成 {@code int}。</p>
 *
 * <p>{@code items} 永不为 {@code null}：无数据时是空列表，页码越界也返回空列表而不是错误。</p>
 *
 * @param items      当前页数据
 * @param page       当前页码，从 1 开始
 * @param size       每页条数
 * @param total      总条数
 * @param totalPages 总页数；{@code total=0} 时为 0
 * @param <T>        列表元素类型
 * @since V2
 */
public record PageView<T>(List<T> items, int page, int size, long total, int totalPages) {

    /** 由「数据 + 分页参数 + 总数」组装视图，并计算总页数（向上取整）。 */
    public static <T> PageView<T> of(List<T> items, int page, int size, long total) {
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageView<>(List.copyOf(items), page, size, total, totalPages);
    }
}
