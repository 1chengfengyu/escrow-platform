package com.escrow.platform.engagement.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 签约，对应表 {@code engagement_contract}（由 {@code V8__v3_engagement_contract.sql} 建好）。
 *
 * <p>不映射生成列 {@code active_slot_id}：它只服务于「有效签约唯一」索引，
 * 由数据库按 {@code status} 自动维护，实体写入时不参与。</p>
 *
 * <p>{@code taskId} 是刻意的冗余（SRS 4.2.2）：撤销 / 到期收口要按任务批量拒绝报名，
 * 有它就不必跨域 join {@code task}。不加 {@code @Data} / {@code @Builder}：理由同 {@code Task}。</p>
 *
 * @since V3
 */
@Getter
@Setter
@TableName("engagement_contract")
public class EngagementContract {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type。 */
    @TableId
    private Long id;

    /** 任务 ID，逻辑外键 task.id；冗余以避免跨域 join。 */
    private Long taskId;

    /** 名额 ID，逻辑外键 task_slot.id；有效签约范围内唯一。 */
    private Long slotId;

    /** 报名 ID，逻辑外键 engagement_application.id；全局唯一（一份报名一生只录用一次）。 */
    private Long applicationId;

    /** 劳动者用户 ID，逻辑外键 platform_user.id。 */
    private Long workerId;

    /** 签约状态；录用成功写入 {@link ContractStatus#CONTRACT_ACTIVE}。 */
    private ContractStatus status;

    /** 生效时间（录用成功时刻），由应用层给值。 */
    private LocalDateTime effectiveAt;

    /** 开始履约时间；NULL = 未开始。 */
    private LocalDateTime startedAt;

    /** 交付时间；NULL = 未交付。 */
    private LocalDateTime deliveredAt;

    /** 创建时间，数据库默认值写入。 */
    private LocalDateTime createdAt;

    /** 更新时间，数据库 ON UPDATE 维护。 */
    private LocalDateTime updatedAt;
}
