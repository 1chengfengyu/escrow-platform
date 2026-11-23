package com.escrow.platform.dispute.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 争议单，对应表 {@code dispute}（由 {@code V4__v2_core_tables.sql} 建好）。
 *
 * <p>一个名额可有多张历史争议单（无唯一键）；「当前是否有未决争议」按
 * {@code status='DISPUTE_PENDING'} 判定。不加 {@code @Data} / {@code @Builder}。</p>
 *
 * @since V4
 */
@Getter
@Setter
@TableName("dispute")
public class Dispute {

    /** 雪花 ID。 */
    @TableId
    private Long id;

    /** 名额 ID，逻辑外键 task_slot.id。 */
    private Long slotId;

    /** 发起方。 */
    private DisputeInitiatorType initiatorType;

    /** 争议理由，1~500 字。 */
    private String reason;

    /** 状态；V4 只写待裁决。 */
    private DisputeStatus status;

    /** 裁决结论 RELEASE / REFUND；未裁决为空。 */
    private DisputeResolution resolution;

    /** 裁决依据，≤500 字；未裁决为空（V6，FR-DISPUTE.CLOSE.01）。 */
    private String resolutionBasis;

    /** 裁决人账号 ID；未裁决为空。 */
    private Long resolverId;

    /** 裁决时间；未裁决为空。 */
    private LocalDateTime resolvedAt;

    /** 发起→撤回累计轮次，0~2。 */
    private Integer withdrawCount;

    /** 创建时间，数据库默认值写入。 */
    private LocalDateTime createdAt;

    /** 更新时间，数据库 ON UPDATE 维护。 */
    private LocalDateTime updatedAt;
}
