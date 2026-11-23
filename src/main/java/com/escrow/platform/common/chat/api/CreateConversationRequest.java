package com.escrow.platform.common.chat.api;

import com.escrow.platform.common.chat.domain.ChatScope;

/**
 * 开启咨询会话请求（V11，补遗 §4 {@code POST /api/conversations}）。
 *
 * <p>用户只能主动发起 {@link ChatScope#INQUIRY}；{@code APPLICATION} / {@code CONTRACT}
 * 由报名、录用事件自动创建，不接受前端传值。</p>
 *
 * @param taskId       任务 ID
 * @param scope        会话范围，缺省视为 {@code INQUIRY}
 * @param targetUserId 雇主发起沟通时的目标劳动者账号 ID；劳动者发起时忽略
 * @since V11
 */
public record CreateConversationRequest(Long taskId, ChatScope scope, Long targetUserId) {
}
