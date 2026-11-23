package com.escrow.platform.fund.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 托管单，对应表 {@code fund_escrow_order}（由 {@code V4__v2_core_tables.sql} 建好）。
 *
 * <p>{@code status} 是 SRS 4.2.2 要求的列，但取值 SRS 未定义；V2 取 HELD / CLOSED 两值，
 * CLOSED 只能在其下全部明细进入终态时由服务层推进，禁止单独修改。</p>
 *
 * <p>不加 {@code @Data}：{@code equals/hashCode} 会破坏 MyBatis 一级缓存的实体身份语义；
 * 也不加 {@code @Builder}：MyBatis-Plus 依赖无参构造（docs/decisions.md #39、docs/tech-stack.md）。</p>
 *
 * <p>状态机见 SRS A.3.1 / A.3.2。</p>
 *
 * @since V2
 */
@Getter
@Setter
@TableName("fund_escrow_order")
public class FundEscrowOrder {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type（docs/decisions.md #14）。 */
    @TableId
    private Long id;

    /** 任务 ID，逻辑外键 task.id；uk_fund_escrow_order_task 保证一任务一张托管单。 */
    private Long taskId;

    /** 托管总额（分）= 单价 × 名额数。 */
    private Long totalAmount;

    /** 服务费率快照（万分数 0~10000）；后续结算只能用这张表的值，不许读任务当前费率。 */
    private Integer feeRate;

    /** 状态：ESCROW_ORDER_HELD / ESCROW_ORDER_CLOSED。 */
    private EscrowOrderStatus status;

    /** 创建时间，数据库默认值写入。 */
    private LocalDateTime createdAt;

    /** 更新时间，数据库 ON UPDATE 维护。 */
    private LocalDateTime updatedAt;
}
