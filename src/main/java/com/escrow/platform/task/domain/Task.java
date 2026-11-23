package com.escrow.platform.task.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 任务，对应表 {@code task}（由 {@code V5__v2_task_tables.sql} 建好）。
 *
 * <p><b>本实体没有 {@code status} / {@code remainingSlots} 字段</b>：任务状态按
 * {@link TaskStatusAggregator}（R0~R12）派生，剩余名额按名额集合计数——
 * 这是 SRS 4.2.2 P1-02 的刻意设计（docs/decisions.md #47），不是遗漏。存成列就是第二真相源，
 * 任一并发路径漏更新都会出现「显示有 1 个名额、点上却报 {@code NO_SLOT_AVAILABLE}」。</p>
 *
 * <p>草稿事实 = {@code publishedAt IS NULL}（R0 判据）；撤单事实 = {@code cancelledAt IS NOT NULL}；
 * {@code recruitingClosed} 是独立属性（= 撤单标志 OR 当前时间 ≥ 结束时间），不是状态（SRS A.3.2）。</p>
 *
 * <p>不加 {@code @Data}：{@code equals/hashCode} 会破坏 MyBatis 一级缓存的实体身份语义，
 * 也不加 {@code @Builder}：MyBatis-Plus 依赖无参构造（docs/decisions.md #39、docs/tech-stack.md）。
 * {@code updatedAt} 只由数据库 {@code ON UPDATE} 维护，禁止 {@code @TableField(fill = ...)}。</p>
 *
 * @since V2
 */
@Getter
@Setter
@TableName("task")
public class Task {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type（docs/decisions.md #14）。 */
    @TableId
    private Long id;

    /** 雇主用户 ID，逻辑外键 platform_user.id；同一雇主可发多个任务，不设唯一键。 */
    private Long employerId;

    /** 任务标题，1~60 字（ck_task_title_len）。 */
    private String title;

    /** 任务描述，1~2000 字（ck_task_desc_len）。 */
    private String description;

    /** 任务类别，1~32 字；SRS 未给取值集，自由文本（docs/decisions.md #47）。 */
    private String category;

    /** 单价，单位分（ck_task_unit_price &gt; 0）。 */
    private Long unitPrice;

    /** 名额数，1~20（ck_task_slot_count）；发布时按它生成名额行（v2-c5）。 */
    private Integer slotCount;

    /** 开始时间（ck_task_time_range：end_time &gt; start_time）。 */
    private LocalDateTime startTime;

    /** 结束时间。 */
    private LocalDateTime endTime;

    /** 工作地点，1~128 字（ck_task_location_len）。 */
    private String workLocation;

    /** 结算说明，≤500 字，可空（ck_task_note_len）。 */
    private String settlementNote;

    /** 费率快照，万分数；草稿为空，v2-c5 发布时写入（ck_task_fee_rate）。 */
    private Integer feeRate;

    /** 发布时间；NULL = 草稿（R0 判据）。 */
    private LocalDateTime publishedAt;

    /** 撤单时间；NULL = 未撤单。 */
    private LocalDateTime cancelledAt;

    /** 招募开关：0=开放 / 1=关闭；独立属性，非状态（SRS A.3.2）。 */
    private Boolean recruitingClosed;

    /** 创建时间，数据库默认值写入。 */
    private LocalDateTime createdAt;

    /** 更新时间，数据库 ON UPDATE 维护。 */
    private LocalDateTime updatedAt;
}
