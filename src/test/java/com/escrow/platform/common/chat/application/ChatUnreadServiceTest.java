package com.escrow.platform.common.chat.application;

import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.chat.api.UnreadCountView;
import com.escrow.platform.common.chat.domain.ChatConversation;
import com.escrow.platform.common.chat.domain.ChatConversationStatus;
import com.escrow.platform.common.chat.domain.ChatScope;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatConversationMapper;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatMessageMapper;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatReadCursorMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 未读服务单元测试（V11，FR-MSG.CHAT.02，补遗 §2.3 / §5.1）。
 *
 * <p>锁「读游标推进到最大消息 ID / 无消息写 0 / 幂等 / 身份过滤 / 非当事人 403 + 审计」；
 * {@code GREATEST} 的并发不回退语义由真库用例证明。</p>
 *
 * @since V11
 */
@ExtendWith(MockitoExtension.class)
class ChatUnreadServiceTest {

    private static final long EMPLOYER_ID = 1001L;
    private static final long WORKER_ID = 2001L;
    private static final long CONVERSATION_ID = 9001L;

    private static final LoginUser EMPLOYER =
            new LoginUser(EMPLOYER_ID, "employer@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final LoginUser WORKER =
            new LoginUser(WORKER_ID, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final LoginUser ADMIN =
            new LoginUser(3001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);

    @Mock
    private ChatConversationMapper conversationMapper;

    @Mock
    private ChatMessageMapper messageMapper;

    @Mock
    private ChatReadCursorMapper readCursorMapper;

    @Mock
    private AuditRecorder auditRecorder;

    @InjectMocks
    private ChatUnreadService unreadService;

    // ==================== markRead ====================

    /** 标记已读：游标推进到当前最大消息 ID。 */
    @Test
    void markReadShouldAdvanceCursorToMaxMessageId() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());
        when(messageMapper.selectMaxId(CONVERSATION_ID)).thenReturn(777L);

        unreadService.markRead(WORKER, CONVERSATION_ID);

        verify(readCursorMapper).upsert(CONVERSATION_ID, WORKER_ID, 777L);
    }

    /** 会话无消息：游标写 0，不传 null。 */
    @Test
    void markReadShouldWriteZeroWhenNoMessage() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());
        when(messageMapper.selectMaxId(CONVERSATION_ID)).thenReturn(null);

        unreadService.markRead(WORKER, CONVERSATION_ID);

        verify(readCursorMapper).upsert(CONVERSATION_ID, WORKER_ID, 0L);
    }

    /** 重复标记已读幂等：两次都成功，游标值相同。 */
    @Test
    void markReadShouldBeIdempotent() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());
        when(messageMapper.selectMaxId(CONVERSATION_ID)).thenReturn(777L);

        unreadService.markRead(WORKER, CONVERSATION_ID);
        unreadService.markRead(WORKER, CONVERSATION_ID);

        verify(readCursorMapper, times(2)).upsert(CONVERSATION_ID, WORKER_ID, 777L);
    }

    /** 会话不存在 → 404，不写游标。 */
    @Test
    void markReadShouldThrow404WhenConversationMissing() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(null);

        ApiException ex = catchThrowableOfType(
                () -> unreadService.markRead(WORKER, CONVERSATION_ID), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verifyNoInteractions(readCursorMapper, messageMapper);
    }

    /** 非当事人 → 403 + CHAT 审计，不写游标（补遗 §5.1）。 */
    @Test
    void markReadShouldRejectNonPartyAndWriteAudit() {
        LoginUser outsider = new LoginUser(4004L, "outsider@example.com",
                UserRole.WORKER, IdentityType.WORKER);
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());

        ApiException ex = catchThrowableOfType(
                () -> unreadService.markRead(outsider, CONVERSATION_ID), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(auditRecorder).record("CHAT", String.valueOf(CONVERSATION_ID), "FORBIDDEN_READ",
                null, null, "userId=" + outsider.userId());
        verify(readCursorMapper, never()).upsert(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** 未登录 → 401。 */
    @Test
    void markReadShouldRejectAnonymous() {
        ApiException ex = catchThrowableOfType(
                () -> unreadService.markRead(null, CONVERSATION_ID), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    // ==================== unreadCount ====================

    /** 雇主身份只统计 employer_id 的会话。 */
    @Test
    void unreadCountShouldCountAsEmployer() {
        when(messageMapper.countUnreadTotal(EMPLOYER_ID, true)).thenReturn(5L);

        UnreadCountView view = unreadService.unreadCount(EMPLOYER);

        assertThat(view.unreadCount()).isEqualTo(5L);
        verify(messageMapper).countUnreadTotal(EMPLOYER_ID, true);
    }

    /** 劳动者身份只统计 worker_id 的会话。 */
    @Test
    void unreadCountShouldCountAsWorker() {
        when(messageMapper.countUnreadTotal(WORKER_ID, false)).thenReturn(2L);

        assertThat(unreadService.unreadCount(WORKER).unreadCount()).isEqualTo(2L);
        verify(messageMapper).countUnreadTotal(WORKER_ID, false);
    }

    /** 管理员身份不得用用户端未读 → 403，不查库。 */
    @Test
    void unreadCountShouldRejectAdminIdentity() {
        ApiException ex = catchThrowableOfType(
                () -> unreadService.unreadCount(ADMIN), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(messageMapper);
    }

    /** 无未读返回 0，不返回 null。 */
    @Test
    void unreadCountShouldReturnZeroWhenNoUnread() {
        when(messageMapper.countUnreadTotal(WORKER_ID, false)).thenReturn(0L);

        assertThat(unreadService.unreadCount(WORKER).unreadCount()).isZero();
    }

    // ==================== unreadOf ====================

    /** 单会话未读数原样透出。 */
    @Test
    void unreadOfShouldReturnCountOfConversation() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());
        when(messageMapper.countUnread(CONVERSATION_ID, WORKER_ID)).thenReturn(3L);

        assertThat(unreadService.unreadOf(WORKER, CONVERSATION_ID).unreadCount()).isEqualTo(3L);
    }

    /** 非当事人读单会话未读 → 403 + 审计，不查消息表。 */
    @Test
    void unreadOfShouldRejectNonPartyAndWriteAudit() {
        LoginUser outsider = new LoginUser(4004L, "outsider@example.com",
                UserRole.WORKER, IdentityType.WORKER);
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());

        ApiException ex = catchThrowableOfType(
                () -> unreadService.unreadOf(outsider, CONVERSATION_ID), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(auditRecorder).record("CHAT", String.valueOf(CONVERSATION_ID), "FORBIDDEN_READ",
                null, null, "userId=" + outsider.userId());
        verifyNoInteractions(messageMapper);
    }

    // ==================== 辅助 ====================

    private static ChatConversation openConversation() {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setScope(ChatScope.INQUIRY);
        conversation.setTaskId(10L);
        conversation.setBizId(0L);
        conversation.setEmployerId(EMPLOYER_ID);
        conversation.setWorkerId(WORKER_ID);
        conversation.setInitiatorId(WORKER_ID);
        conversation.setStatus(ChatConversationStatus.OPEN);
        return conversation;
    }
}
