package com.escrow.platform.common.notification.api;

/**
 * 未读站内信计数（V7，FR-MSG.LIST.01）。
 *
 * @param unreadCount 未读条数
 * @since V7
 */
public record UnreadCountView(long unreadCount) {
}
