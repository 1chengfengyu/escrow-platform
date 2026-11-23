package com.escrow.platform.engagement.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 报名，对应表 {@code engagement_application}（由 {@code V7__v3_engagement_application.sql} 建好）。
 *
 * <p>不映射生成列 {@code active_task_id / active_worker_id}：它们只服务于唯一索引，
 * 由数据库按 {@code status} 自动维护，实体写入时不参与。</p>
 *
 * <p>报名不占名额：本实体与 {@code task_slot} 无写入关系（FR-SIGN.APPLY.04），
 * 名额占用只发生在录用（v3-c2）。</p>
 *
 * <p>不加 {@code @Data} / {@code @Builder}：理由同 {@code Task}（docs/decisions.md #39、docs/tech-stack.md）。</p>
 *
 * @since V3
 */
@Getter
@Setter
@TableName("engagement_application")
public class EngagementApplication {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type。 */
    @TableId
    private Long id;

    /** 任务 ID，逻辑外键 task.id。 */
    private Long taskId;

    /** 劳动者用户 ID，逻辑外键 platform_user.id。 */
    private Long workerId;

    /** 报名状态；新建恒为 {@link ApplicationStatus#APPLY_PENDING}。 */
    private ApplicationStatus status;

    /** 报名时间，数据库默认值写入。 */
    private LocalDateTime appliedAt;

    /** 撤回时间；NULL = 未撤回。 */
    private LocalDateTime withdrawnAt;

    /** 创建时间，数据库默认值写入。 */
    private LocalDateTime createdAt;

    /** 更新时间，数据库 ON UPDATE 维护。 */
    private LocalDateTime updatedAt;
}
