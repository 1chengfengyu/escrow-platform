package com.escrow.platform.common.notification.api;

import java.time.LocalDateTime;

/**
 * 死信视图（V8）。
 *
 * <p>{@code payload} 只截前 500 字符：管理端用来判断是否需要重放，
 * 不需要在列表里搬运整条报文。</p>
 *
 * @param id             死信 ID
 * @param topic          原主题
 * @param payload        原始消息体（截断）
 * @param errorMessage   失败原因
 * @param replayCount    累计重放次数
 * @param lastReplayedAt 最近一次重放时间，未重放为空
 * @param createdAt      落表时间
 * @since V8
 */
public record DeadLetterView(Long id, String topic, String payload, String errorMessage,
                             int replayCount, LocalDateTime lastReplayedAt,
                             LocalDateTime createdAt) {
}
