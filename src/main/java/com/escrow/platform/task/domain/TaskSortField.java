package com.escrow.platform.task.domain;

/**
 * 任务列表排序字段白名单（FR-TASK.LIST.04，docs/decisions.md #48）。
 *
 * <p>只接受线上小写名：{@code PUBLISHED_AT} / {@code publishedAt} 一律非法，
 * 防止「前端传什么就排什么」变成隐式 SQL 注入面。</p>
 *
 * <p>不落库、不 import MyBatis：它只是入参白名单，不是持久化枚举。</p>
 *
 * @since V2
 */
public enum TaskSortField {

    /** 按发布时间排序（默认）。 */
    PUBLISHED_AT("published_at"),

    /** 按单价排序。 */
    UNIT_PRICE("unit_price");

    private final String wireName;

    TaskSortField(String wireName) {
        this.wireName = wireName;
    }

    public String getWireName() {
        return wireName;
    }

    /** 线上名转枚举；不在白名单内抛 {@link IllegalArgumentException}，由调用方转 400。 */
    public static TaskSortField fromWire(String value) {
        for (TaskSortField f : values()) {
            if (f.wireName.equals(value)) {
                return f;
            }
        }
        throw new IllegalArgumentException("unsupported sort: " + value);
    }
}
