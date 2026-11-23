package com.escrow.platform.common.audit.api;

import java.time.LocalDateTime;

/**
 * 审计流水视图（V7）。
 *
 * @param id           记录 ID
 * @param bizType      业务类型
 * @param bizNo        业务单号
 * @param action       动作
 * @param fromStatus   变更前状态
 * @param toStatus     变更后状态
 * @param operatorId   操作人
 * @param operatorRole 操作人身份
 * @param detail       补充说明
 * @param createdAt    记录时间
 * @since V7
 */
public record AuditLogView(Long id, String bizType, String bizNo, String action, String fromStatus,
                           String toStatus, Long operatorId, String operatorRole, String detail,
                           LocalDateTime createdAt) {
}
