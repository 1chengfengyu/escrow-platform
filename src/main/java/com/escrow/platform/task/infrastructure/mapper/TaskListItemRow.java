package com.escrow.platform.task.infrastructure.mapper;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 任务列表查询行（{@code selectTaskPage} 的 resultType）。
 *
 * <p>必须是<b>普通类 + 无参构造 + setter</b>，不用 record：MyBatis 自动映射依赖 setter，
 * record 需要写 {@code <constructor>} 映射，本卡不引入这层复杂度。</p>
 *
 * <p>列名下划线转驼峰由 {@code map-underscore-to-camel-case: true} 完成，
 * 不写手工 {@code resultMap}。</p>
 *
 * @since V2
 */
@Getter
@Setter
public class TaskListItemRow {

    private Long id;
    private String title;
    private String category;
    private Long unitPrice;
    private String workLocation;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime publishedAt;
    private Integer remainingSlots;
    private Boolean hasOpenDispute;
    private Boolean hasSettlementAbnormal;
}
