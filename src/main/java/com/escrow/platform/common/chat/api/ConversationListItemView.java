package com.escrow.platform.common.chat.api;

import com.escrow.platform.common.chat.domain.ChatConversationStatus;
import com.escrow.platform.common.chat.domain.ChatScope;

import java.time.LocalDateTime;

/**
 * 会话列表项（V11，补遗 §4 {@code GET /api/conversations}）。
 *
 * <p>只放列表渲染需要的字段：任务标题留给详情，避免列表为每一行再查一次 task 域（N+1）。
 * 未读数由 CARD-07 填充，本卡恒为 {@code 0}。</p>
 *
 * @param id            会话 ID
 * @param scope         会话范围，即沟通阶段
 * @param taskId        任务 ID
 * @param status        会话状态
 * @param lastMessageAt 最后消息时间，可为 {@code null}
 * @param unreadCount   未读条数
 * @since V11
 */
public record ConversationListItemView(Long id, ChatScope scope, Long taskId,
                                       ChatConversationStatus status, LocalDateTime lastMessageAt,
                                       long unreadCount) {
}
