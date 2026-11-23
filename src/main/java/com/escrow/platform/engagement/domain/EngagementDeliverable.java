package com.escrow.platform.engagement.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 交付物，对应表 {@code engagement_deliverable}（由 {@code V10__v3_engagement_deliverable.sql} 建好）。
 *
 * <p>一个签约至多一份（{@code UNIQUE(contract_id)}）；{@code imageUrls} 在库里是 JSON 数组文本，
 * 由应用层序列化 / 反序列化，实体本身保持 {@code String}，不把 Jackson 带进 domain。</p>
 *
 * <p>不加 {@code @Data} / {@code @Builder}：理由同 {@code Task}（docs/decisions.md #39、docs/tech-stack.md）。</p>
 *
 * @since V3
 */
@Getter
@Setter
@TableName("engagement_deliverable")
public class EngagementDeliverable {

    /** 雪花 ID，全局 {@code assign_id}。 */
    @TableId
    private Long id;

    /** 签约 ID，逻辑外键 engagement_contract.id；唯一。 */
    private Long contractId;

    /** 劳动者用户 ID，逻辑外键 platform_user.id。 */
    private Long workerId;

    /** 图片 URL 列表的 JSON 数组文本（1~9 个）。 */
    private String imageUrls;

    /** 文字说明（≤500 字），可为空。 */
    private String description;

    /** 提交时间，验收倒计时起点。 */
    private LocalDateTime submittedAt;

    /** 创建时间，数据库默认值写入。 */
    private LocalDateTime createdAt;
}
