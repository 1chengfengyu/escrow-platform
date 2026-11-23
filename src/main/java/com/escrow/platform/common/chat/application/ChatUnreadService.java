package com.escrow.platform.common.chat.application;

import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.chat.api.UnreadCountView;
import com.escrow.platform.common.chat.domain.ChatConversation;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatConversationMapper;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatMessageMapper;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatReadCursorMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 未读服务（V11，FR-MSG.CHAT.02，补遗 §2.3 / §4 / §5.1）。
 *
 * <p><b>未读用读游标推导</b>，不回写消息表：消息表只追加，已读状态属于「人 × 会话」，
 * 由 {@code chat_read_cursor} 承载。这样同一会话两个当事人的已读互不影响，也不需要
 * 给每条消息加 {@code read_flag}。</p>
 *
 * <p>标记已读把游标推进到当前最大消息 ID，SQL 用 {@code GREATEST} 只增不减，
 * 重复调用天然幂等；无消息时写 0。</p>
 *
 * @since V11
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatUnreadService {

    private static final String AUDIT_BIZ_TYPE = "CHAT";
    private static final String AUDIT_ACTION_FORBIDDEN = "FORBIDDEN_READ";
    private static final String UNAUTHENTICATED_MESSAGE = "请先登录";
    private static final String FORBIDDEN_MESSAGE = "无权访问该会话";
    private static final String NOT_FOUND_MESSAGE = "会话不存在";

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatReadCursorMapper readCursorMapper;
    private final AuditRecorder auditRecorder;

    /**
     * 标记整个会话已读（FR-MSG.CHAT.02，补遗 §5.2）。
     *
     * <p>游标推进到当前最大消息 ID；无消息写 0。{@code GREATEST} 保证并发下不回退，
     * 重复调用返回相同结果、不报错。</p>
     *
     * @param current        会话主体
     * @param conversationId 会话 ID
     * @throws ApiException 401 未登录 / 404 会话不存在 / 403 非当事人
     */
    public void markRead(LoginUser current, Long conversationId) {
        Long userId = requireLogin(current);
        loadConversationForParty(conversationId, userId);
        Long maxMessageId = messageMapper.selectMaxId(conversationId);
        long cursor = maxMessageId == null ? 0L : maxMessageId;
        readCursorMapper.upsert(conversationId, userId, cursor);
        log.info("会话标记已读 conversationId={} userId={} cursor={}", conversationId, userId, cursor);
    }

    /**
     * 当前身份下全部会话的未读总数（补遗 §4 导航角标）。
     *
     * @param current 会话主体
     * @return 未读总数
     * @throws ApiException 401 未登录 / 403 当前身份非雇主或劳动者
     */
    public UnreadCountView unreadCount(LoginUser current) {
        Long userId = requireLogin(current);
        requirePartyIdentity(current);
        boolean asEmployer = current.currentIdentity() == IdentityType.EMPLOYER;
        return new UnreadCountView(messageMapper.countUnreadTotal(userId, asEmployer));
    }

    /**
     * 单个会话的未读数（补遗 §4 会话列表角标）。
     *
     * @param current        会话主体
     * @param conversationId 会话 ID
     * @return 未读条数
     * @throws ApiException 401 未登录 / 404 会话不存在 / 403 非当事人
     */
    public UnreadCountView unreadOf(LoginUser current, Long conversationId) {
        Long userId = requireLogin(current);
        loadConversationForParty(conversationId, userId);
        return new UnreadCountView(messageMapper.countUnread(conversationId, userId));
    }

    // ==================== 内部 ====================

    private ChatConversation loadConversationForParty(Long conversationId, Long userId) {
        if (conversationId == null) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "conversationId 不能为空");
        }
        ChatConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        boolean party = userId.equals(conversation.getEmployerId()) || userId.equals(conversation.getWorkerId());
        if (!party) {
            log.warn("越权读取未读 conversationId={} userId={}", conversationId, userId);
            auditRecorder.record(AUDIT_BIZ_TYPE, String.valueOf(conversationId), AUDIT_ACTION_FORBIDDEN,
                    null, null, "userId=" + userId);
            throw new ApiException(ErrorCode.FORBIDDEN, FORBIDDEN_MESSAGE);
        }
        return conversation;
    }

    private static Long requireLogin(LoginUser current) {
        if (current == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_MESSAGE);
        }
        return current.userId();
    }

    private static void requirePartyIdentity(LoginUser current) {
        IdentityType identity = current.currentIdentity();
        if (identity != IdentityType.EMPLOYER && identity != IdentityType.WORKER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅雇主或劳动者身份可访问会话");
        }
    }
}
