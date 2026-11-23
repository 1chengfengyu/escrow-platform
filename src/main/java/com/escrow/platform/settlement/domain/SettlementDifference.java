package com.escrow.platform.settlement.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对账差异单，对应表 {@code settlement_difference}（由 {@code V4__v2_core_tables.sql} 建好）。
 *
 * <p>{@code AMOUNT_MISMATCH} 必须同时有本地金额与通道金额；已处理只允许补记 / 冲正 / 差额调整，
 * 已忽略只允许 RECON_IGNORE；四处置的借贷科目按 SRS 3.4.6 映射表，实现方不得自选。</p>
 *
 * <p>不加 {@code @Data}：{@code equals/hashCode} 会破坏 MyBatis 一级缓存的实体身份语义；
 * 也不加 {@code @Builder}：MyBatis-Plus 依赖无参构造（docs/decisions.md #39、docs/tech-stack.md）。</p>
 *
 * <p>状态机见 SRS 3.4.6 / 4.2.2「差异单」。</p>
 *
 * @since V2
 */
@Getter
@Setter
@TableName("settlement_difference")
public class SettlementDifference {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type（docs/decisions.md #14）。 */
    @TableId
    private Long id;

    /** 对账日（DATE，唯一用 LocalDate 的字段）。 */
    private LocalDate reconDate;

    /** 差异类型，见 {@link DifferenceType}。 */
    private DifferenceType diffType;

    /** 本地记录号：打款单请求号 payout_request_no。 */
    private String localRecordNo;

    /** 通道记录号：通道流水号，缺失为 null。 */
    private String channelRecordNo;

    /** 本地金额（分），可为 null。 */
    private Long localAmount;

    /** 通道金额（分），可为 null。 */
    private Long channelAmount;

    /** 状态，见 {@link DifferenceStatus}。 */
    private DifferenceStatus status;

    /** 处置结果，待处理为 null，见 {@link DifferenceResolution}。 */
    private DifferenceResolution resolution;

    /** 处置人账号 ID，待处理为 null。 */
    private Long resolverId;

    /** 处置时间，待处理为 null。 */
    private LocalDateTime resolvedAt;

    /** 创建时间，数据库默认值写入。 */
    private LocalDateTime createdAt;

    /** 更新时间，数据库 ON UPDATE 维护。 */
    private LocalDateTime updatedAt;
}
