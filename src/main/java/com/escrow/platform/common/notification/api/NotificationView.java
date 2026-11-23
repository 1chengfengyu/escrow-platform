package com.escrow.platform.common.notification.api;

import java.time.LocalDateTime;

/**
 * 站内信视图（V7，FR-MSG.LIST.01）。
 *
 * @param id        站内信 ID
 * @param bizType   业务类型
 * @param bizNo     业务单号
 * @param title     标题
 * @param content   正文
 * @param read      是否已读
 * @param createdAt 创建时间
 * @since V7
 */
public record NotificationView(
        Long id,
        String bizType,
        String bizNo,
        String title,
        String content,
        boolean read,
        LocalDateTime createdAt) {
}
