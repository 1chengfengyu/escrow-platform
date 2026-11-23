package com.escrow.platform.settlement.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 打款单，对应表 {@code payout_order}（由 {@code V4__v2_core_tables.sql} 建好）。
 *
 * <p>一次尝试一张单；至多一条成功的部分唯一由生成列 {@code success_settlement_id} +
 * {@code uk_payout_success} 兜住；重试 = 新建一张 + 新请求号。</p>
 *
 * <p>不加 {@code @Data}：{@code equals/hashCode} 会破坏 MyBatis 一级缓存的实体身份语义；
 * 也不加 {@code @Builder}：MyBatis-Plus 依赖无参构造（docs/decisions.md #39、docs/tech-stack.md）。</p>
 *
 * <p>状态机见 SRS A.3.5。</p>
 *
 * @since V2
 */
@Getter
@Setter
@TableName("payout_order")
public class PayoutOrder {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type（docs/decisions.md #14）。 */
    @TableId
    private Long id;

    /** 结算单 ID，逻辑外键 settlement.id；不对它整体加唯一约束（一张结算单可以有多条失败/未知尝试）。 */
    private Long settlementId;

    /** payout_request_no，全局唯一，每次尝试新生成；重试不复用。 */
    private String requestNo;

    /** 尝试序号，从 1 开始，同一结算单内递增。 */
    private Integer attemptSeq;

    /** 请求金额 X（分）。 */
    private Long amount;

    /** 通道实际出款金额 Y（分），确认前为 null；成功态库层 ck_payout_success_actual 强制非空。 */
    private Long actualAmount;

    /** 通道原始结果，未回调时为 null；查证后的判定结论（NOT_FOUND / AMOUNT_MISMATCH）不落此列。 */
    private ChannelResult channelResult;

    /** 状态，见 {@link PayoutStatus}。 */
    private PayoutStatus status;

    /** 进入当前状态的时间；账龄只按它与 NOW() 之差算，不许按 created_at 算。 */
    private LocalDateTime statusEnteredAt;

    /** 创建时间，数据库默认值写入。 */
    private LocalDateTime createdAt;

    /** 更新时间，数据库 ON UPDATE 维护。 */
    private LocalDateTime updatedAt;
}
