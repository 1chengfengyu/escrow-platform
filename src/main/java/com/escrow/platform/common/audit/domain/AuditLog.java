package com.escrow.platform.common.audit.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 审计流水，对应表 {@code audit_log}（V17，FR-AUDIT.MONEY.01 / FR-ADMIN.AUDIT.01）。
 *
 * <p>只追加：没有更新与删除入口；查询按业务单号或操作人检索。</p>
 *
 * @since V7
 */
@Getter
@Setter
@TableName("audit_log")
public class AuditLog {

    /** 雪花 ID。 */
    @TableId
    private Long id;

    /** 业务类型。 */
    private String bizType;

    /** 业务单号或资源 ID。 */
    private String bizNo;

    /** 动作。 */
    private String action;

    /** 变更前状态。 */
    private String fromStatus;

    /** 变更后状态。 */
    private String toStatus;

    /** 操作人账号 ID；系统任务为空。 */
    private Long operatorId;

    /** 操作人身份。 */
    private String operatorRole;

    /** 补充说明。 */
    private String detail;

    /** 记录时间。 */
    private LocalDateTime createdAt;
}
