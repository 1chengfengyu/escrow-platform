package com.escrow.platform.task.api;

import java.time.LocalDateTime;

/**
 * 任务列表条目（FR-TASK.LIST.01~.04，docs/decisions.md #48）。
 *
 * <p><b>不含 {@code description}</b>：列表只需要标题、类别、单价、地点、起止时间、
 * 发布时间、剩余名额与两个标记；描述只在详情返回，避免每页多拖 2000 字。</p>
 *
 * <p>{@code Long} 字段由 {@code JacksonConfig} 全局字符串化，不挂字段级注解。</p>
 *
 * @param id                     任务 ID
 * @param title                  标题
 * @param category               类别
 * @param unitPrice              单价（分）
 * @param workLocation           工作地点
 * @param startTime              开始时间
 * @param endTime                结束时间
 * @param publishedAt            发布时间
 * @param remainingSlots         剩余名额（待占用名额数）
 * @param hasOpenDispute         是否存在未裁决争议（V2 恒 false）
 * @param hasSettlementAbnormal  是否存在待裁决名额
 * @since V2
 */
public record TaskListItemView(
        Long id, String title, String category, Long unitPrice, String workLocation,
        LocalDateTime startTime, LocalDateTime endTime, LocalDateTime publishedAt,
        Integer remainingSlots, boolean hasOpenDispute, boolean hasSettlementAbnormal) {
}
