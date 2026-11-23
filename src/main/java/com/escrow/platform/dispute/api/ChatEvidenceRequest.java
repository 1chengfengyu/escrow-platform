package com.escrow.platform.dispute.api;

import java.util.List;

/**
 * 从会话取证请求（V11，CARD-12，补遗 §5.4）。
 *
 * <p>只收消息 ID：正文由服务端从 {@code chat_message} 复制并截断，
 * 不接受客户端回传的内容，避免伪造聊天记录。</p>
 *
 * @param messageIds 待复制的消息 ID，1~20 条
 * @since V11
 */
public record ChatEvidenceRequest(List<Long> messageIds) {
}
