package com.escrow.platform.settlement.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 结算单，对应表 {@code settlement}（由 {@code V4__v2_core_tables.sql} 建好）。
 *
 * <p>{@code fee_amount + net_amount = payable_amount} 由库层 CHECK 兜住；{@code net_amount} 是
 * 应打款金额，实际出款以 {@code payout_order.actual_amount} 为准。</p>
 *
 * <p>不加 {@code @Data}：{@code equals/hashCode} 会破坏 MyBatis 一级缓存的实体身份语义；
 * 也不加 {@code @Builder}：MyBatis-Plus 依赖无参构造（docs/decisions.md #39、docs/tech-stack.md）。</p>
 *
 * <p>状态机见 SRS A.3.4。</p>
 *
 * @since V2
 */
@Getter
@Setter
@TableName("settlement")
public class Settlement {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type（docs/decisions.md #14）。 */
    @TableId
    private Long id;

    /** 名额 ID，逻辑外键 task_slot.id；uk_settlement_slot 保证一名额一张结算单。 */
    private Long slotId;

    /** 结算单号，全局唯一；兼作 payment_intent_no，不另设列。 */
    private String settlementNo;

    /** 应结金额（分）。 */
    private Long payableAmount;

    /** 服务费（分）= floor(应结 × 费率 ÷ 10000)。 */
    private Long feeAmount;

    /** 实付金额（应打款金额，分）；实际出款见 payout_order.actual_amount。 */
    private Long netAmount;

    /** 服务费率快照（万分数）。 */
    private Integer feeRate;

    /** 状态，见 {@link SettlementStatus}。 */
    private SettlementStatus status;

    /** 创建时间，数据库默认值写入。 */
    private LocalDateTime createdAt;

    /** 更新时间，数据库 ON UPDATE 维护。 */
    private LocalDateTime updatedAt;
}
