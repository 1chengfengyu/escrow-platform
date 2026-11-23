package com.escrow.platform.common.notification;

import java.time.LocalDateTime;

/**
 * 站内信事件（V5，非资金事件）。
 *
 * <p>字段刻意扁平且全部字符串 / 数字：事件要跨进程传输，
 * 传实体或枚举会把结算域的类结构泄漏给消费方。</p>
 *
 * @param eventId   事件 ID，消费幂等键
 * @param userId    接收人账号 ID
 * @param bizType   业务类型（PAYOUT / SETTLEMENT / DISPUTE）
 * @param bizNo     业务单号
 * @param title     标题
 * @param content   正文
 * @param createdAt 事件产生时间
 * @since V5
 */
public record NotificationMessage(String eventId, Long userId, String bizType, String bizNo,
                                  String title, String content, LocalDateTime createdAt) {
}
