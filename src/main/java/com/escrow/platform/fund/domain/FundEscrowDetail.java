package com.escrow.platform.fund.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 托管明细，对应表 {@code fund_escrow_detail}（由 {@code V4__v2_core_tables.sql} 建好）。
 *
 * <p>释放 / 退款的最小单位；已释放 / 已退款是终态，唯一出边是差异①回退重试（SRS A.3.3）。</p>
 *
 * <p>不加 {@code @Data}：{@code equals/hashCode} 会破坏 MyBatis 一级缓存的实体身份语义；
 * 也不加 {@code @Builder}：MyBatis-Plus 依赖无参构造（docs/decisions.md #39、docs/tech-stack.md）。</p>
 *
 * <p>状态机见 SRS A.3.3。</p>
 *
 * @since V2
 */
@Getter
@Setter
@TableName("fund_escrow_detail")
public class FundEscrowDetail {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type（docs/decisions.md #14）。 */
    @TableId
    private Long id;

    /** 托管单 ID，逻辑外键 fund_escrow_order.id。 */
    private Long escrowOrderId;

    /** 名额 ID，逻辑外键 task_slot.id；uk_fund_escrow_detail_slot 保证一名额一条明细。 */
    private Long slotId;

    /** 该名额冻结金额（分），ck_fund_escrow_detail_amount 兜住 > 0。 */
    private Long amount;

    /** 六态，见 {@link EscrowDetailStatus}。 */
    private EscrowDetailStatus status;

    /** 创建时间，数据库默认值写入。 */
    private LocalDateTime createdAt;

    /** 更新时间，数据库 ON UPDATE 维护。 */
    private LocalDateTime updatedAt;
}
