package com.escrow.platform.common.chat.api;

import com.escrow.platform.common.chat.domain.ChatMessageType;

import java.time.LocalDateTime;

/**
 * 消息视图（V11，补遗 §4 {@code GET/POST /api/conversations/{id}/messages}）。
 *
 * <p>{@code contactHint} 只是「疑似联系方式」的提示位：命中时消息照常发送、正文不改写，
 * 前端据此提示用户走平台内沟通（补遗 §5.3）。</p>
 *
 * @param id             消息 ID
 * @param conversationId 会话 ID
 * @param senderId       发送人账号 ID；系统消息为 {@code 0}
 * @param msgType        消息类型
 * @param content        消息正文
 * @param contactHint    是否疑似包含联系方式
 * @param createdAt      发送时间
 * @since V11
 */
public record MessageView(Long id, Long conversationId, Long senderId, ChatMessageType msgType,
                          String content, boolean contactHint, LocalDateTime createdAt) {
}
