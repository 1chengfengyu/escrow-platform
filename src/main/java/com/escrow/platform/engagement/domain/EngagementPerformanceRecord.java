package com.escrow.platform.engagement.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 履约记录，对应表 {@code engagement_performance_record}（由 {@code V9__v3_performance_record.sql} 建好）。
 *
 * <p>只追加，不更新、不删除；{@code (contractId, eventType)} 唯一，
 * 同一签约的同类事件至多一条（重试或并发由唯一键兜底）。</p>
 *
 * <p>不加 {@code @Data} / {@code @Builder}：理由同 {@code Task}（docs/decisions.md #39、docs/tech-stack.md）。</p>
 *
 * @since V3
 */
@Getter
@Setter
@TableName("engagement_performance_record")
public class EngagementPerformanceRecord {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type。 */
    @TableId
    private Long id;

    /** 劳动者用户 ID，逻辑外键 platform_user.id。 */
    private Long workerId;

    /** 签约 ID，逻辑外键 engagement_contract.id。 */
    private Long contractId;

    /** 履约事件类型；本卡写开始 / 取消 / 超时取消。 */
    private PerformanceEventType eventType;

    /** 事件发生时间（业务时间，由应用层给值）。 */
    private LocalDateTime occurredAt;

    /** 创建时间，数据库默认值写入。 */
    private LocalDateTime createdAt;
}
