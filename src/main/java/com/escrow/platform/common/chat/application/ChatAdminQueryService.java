package com.escrow.platform.common.chat.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.chat.api.MessageView;
import com.escrow.platform.common.chat.domain.ChatConversation;
import com.escrow.platform.common.chat.domain.ChatMessage;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatConversationMapper;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatMessageMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理端只读取证（V11，补遗 §5.3，FR-MSG.CHAT.02 配套）。
 *
 * <p>管理员可以读任意会话的消息用于争议取证，但<b>没有任何写入口</b>：本类只有分页查询，
 * 且每次查询写一条 {@code CHAT/ADMIN_VIEW} 审计（页码与消息 ID 区间，<b>不落正文</b>）。</p>
 *
 * <p>不得标 {@code @Transactional(readOnly = true)}：审计随查询一起写库，
 * 只读事务会让审计被静默丢弃。</p>
 *
 * @since V11
 */
@Service
@RequiredArgsConstructor
public class ChatAdminQueryService {

    /** 与用户端一致，单页最多 50 条；管理员也不例外。 */
    private static final int MAX_PAGE_SIZE = 50;

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final AuditRecorder auditRecorder;

    /**
     * 管理员按会话分页读取消息（只读 + 审计）。
     *
     * @param current        当前登录主体，必须是 ADMIN 身份
     * @param conversationId 会话 ID
     * @param page           页码，从 1 开始；小于 1 按 1 处理
     * @param size           每页条数，夹到 1~50
     * @return 消息分页视图
     * @throws ApiException 未登录 401 / 非管理员 403 / 会话不存在 404
     */
    public PageView<MessageView> pageMessages(LoginUser current, Long conversationId, int page, int size) {
        if (current == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "请先登录");
        }
        if (current.currentIdentity() != IdentityType.ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅管理员可查看会话");
        }
        ChatConversation conversation = conversationId == null ? null
                : conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在");
        }

        int pageNo = Math.max(1, page);
        int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        List<ChatMessage> rows = messageMapper.selectPageAsc(conversationId,
                (pageNo - 1) * pageSize, pageSize);
        long total = messageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId));

        auditRecorder.record("CHAT", String.valueOf(conversationId), "ADMIN_VIEW",
                null, null, auditDetail(pageNo, pageSize, rows));
        return PageView.of(rows.stream().map(ChatAdminQueryService::toView).toList(),
                pageNo, pageSize, total);
    }

    /** 审计只记位置信息：页码、页大小、本页消息 ID 区间。 */
    private static String auditDetail(int page, int size, List<ChatMessage> rows) {
        String range = rows.isEmpty()
                ? "none"
                : rows.get(0).getId() + "-" + rows.get(rows.size() - 1).getId();
        return "page=" + page + " size=" + size + " messageIds=" + range;
    }

    private static MessageView toView(ChatMessage message) {
        return new MessageView(message.getId(), message.getConversationId(), message.getSenderId(),
                message.getMsgType(), message.getContent(),
                message.getContactHint() != null && message.getContactHint() == 1,
                message.getCreatedAt());
    }
}
