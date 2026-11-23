package com.escrow.platform.common.chat.api;

/**
 * 发送消息请求（V11，补遗 §4 {@code POST /api/conversations/{id}/messages}）。
 *
 * <p>{@code clientMessageId} 由前端生成，用于 10 分钟内的发送幂等：网络重试不会产生重复消息。
 * 为空则不启用幂等。</p>
 *
 * @param content         消息正文，1~1000 字
 * @param clientMessageId 客户端消息 ID，可空
 * @since V11
 */
public record SendMessageRequest(String content, String clientMessageId) {
}
