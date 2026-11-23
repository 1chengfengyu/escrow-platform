package com.escrow.platform.task.domain;

/**
 * 排序方向（FR-TASK.LIST.04，docs/decisions.md #48）。
 *
 * <p>只接受小写 {@code asc} / {@code desc}：{@code ASC} / {@code Asc} 一律非法。
 * 与 {@link TaskSortField} 一样只是入参白名单，不落库。</p>
 *
 * @since V2
 */
public enum SortDirection {

    /** 升序。 */
    ASC,

    /** 降序（默认）。 */
    DESC;

    /** 线上名转枚举；只认小写，其余抛 {@link IllegalArgumentException}，由调用方转 400。 */
    public static SortDirection fromWire(String value) {
        if ("asc".equals(value)) {
            return ASC;
        }
        if ("desc".equals(value)) {
            return DESC;
        }
        throw new IllegalArgumentException("unsupported order: " + value);
    }
}
