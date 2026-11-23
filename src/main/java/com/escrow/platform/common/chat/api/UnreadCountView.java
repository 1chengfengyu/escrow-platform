package com.escrow.platform.common.chat.api;

/**
 * 未读计数视图（V11，补遗 §4）。
 *
 * <p>未读由读游标推导，不在消息表回写已读标记：
 * {@code id > last_read_message_id AND sender_id <> 当前用户}。</p>
 *
 * @param unreadCount 未读条数
 * @since V11
 */
public record UnreadCountView(long unreadCount) {
}
