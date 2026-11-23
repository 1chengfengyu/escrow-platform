package com.escrow.platform.common.chat.api;

import com.escrow.platform.common.chat.domain.ChatConversationStatus;
import com.escrow.platform.common.chat.domain.ChatScope;

import java.time.LocalDateTime;

/**
 * 会话详情视图（V11，补遗 §4 {@code GET /api/conversations/{id}}）。
 *
 * <p>任务标题经 task 域只读入口读取，不 join {@code task} 表；双方只给公开昵称，
 * 手机号、证件、真实姓名一律不带出（补遗 §5.1，FR-ACCT.PROFILE.02）。</p>
 *
 * @param id                会话 ID
 * @param scope             会话范围，即沟通阶段
 * @param taskId            任务 ID
 * @param taskTitle         任务标题
 * @param employerId        雇主账号 ID
 * @param workerId          劳动者账号 ID
 * @param employerNickname  雇主公开昵称
 * @param workerNickname    劳动者公开昵称
 * @param status            会话状态
 * @param lastMessageAt     最后消息时间，可为 {@code null}
 * @param createdAt         创建时间
 * @since V11
 */
public record ConversationView(Long id, ChatScope scope, Long taskId, String taskTitle,
                               Long employerId, Long workerId, String employerNickname,
                               String workerNickname, ChatConversationStatus status,
                               LocalDateTime lastMessageAt, LocalDateTime createdAt) {
}
