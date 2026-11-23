package com.escrow.platform.common.security.api;

import java.time.LocalDateTime;

/**
 * 安全事件视图（V7）。
 *
 * @param id         事件 ID
 * @param userId     触发账号 ID
 * @param role       触发时身份
 * @param method     HTTP 方法
 * @param path       请求路径
 * @param reason     拒绝原因
 * @param targetType 目标资源类型
 * @param targetId   目标资源 ID
 * @param ip         来源 IP
 * @param createdAt  发生时间
 * @since V7
 */
public record SecurityEventView(Long id, Long userId, String role, String method, String path,
                                String reason, String targetType, Long targetId, String ip,
                                LocalDateTime createdAt) {
}
