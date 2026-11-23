package com.escrow.platform.common.chat.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.chat.api.MessageView;
import com.escrow.platform.common.chat.domain.ChatConversation;
import com.escrow.platform.common.chat.domain.ChatConversationStatus;
import com.escrow.platform.common.chat.domain.ChatMessage;
import com.escrow.platform.common.chat.domain.ChatMessageType;
import com.escrow.platform.common.chat.domain.ChatScope;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatConversationMapper;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatMessageMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端只读取证测试（V11，补遗 §5.3）。
 *
 * @since V11
 */
@ExtendWith(MockitoExtension.class)
class ChatAdminQueryServiceTest {

    private static final long CONVERSATION_ID = 9001L;
    private static final LoginUser ADMIN =
            new LoginUser(9000L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);
    private static final LoginUser WORKER =
            new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Mock
    private ChatConversationMapper conversationMapper;
    @Mock
    private ChatMessageMapper messageMapper;
    @Mock
    private AuditRecorder auditRecorder;

    @InjectMocks
    private ChatAdminQueryService service;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, ChatMessage.class);
    }

    /** 管理员分页读取并写审计；审计只记页码与 ID 区间，不含正文。 */
    @Test
    void adminShouldPageAndAuditWithoutContent() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        when(messageMapper.selectPageAsc(CONVERSATION_ID, 0, 20))
                .thenReturn(List.of(message(101L, "第一条正文"), message(102L, "第二条正文")));
        when(messageMapper.selectCount(any(Wrapper.class))).thenReturn(2L);

        PageView<MessageView> page = service.pageMessages(ADMIN, CONVERSATION_ID, 1, 20);

        assertThat(page.items()).extracting(MessageView::id).containsExactly(101L, 102L);
        assertThat(page.total()).isEqualTo(2L);
        assertThat(page.totalPages()).isEqualTo(1);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditRecorder).record(eq("CHAT"), eq(String.valueOf(CONVERSATION_ID)),
                eq("ADMIN_VIEW"), isNull(), isNull(), detail.capture());
        assertThat(detail.getValue())
                .contains("page=1")
                .contains("messageIds=101-102")
                .doesNotContain("第一条正文")
                .doesNotContain("第二条正文");
    }

    /** 非管理员 403，且不写取证审计、不查消息。 */
    @Test
    void nonAdminShouldBeForbiddenWithoutAudit() {
        assertThatThrownBy(() -> service.pageMessages(WORKER, CONVERSATION_ID, 1, 20))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(auditRecorder, never()).record(any(), any(), any(), any(), any(), any());
        verify(messageMapper, never()).selectPageAsc(any(), anyInt(), anyInt());
    }

    /** 未登录 401。 */
    @Test
    void anonymousShouldBeUnauthenticated() {
        assertThatThrownBy(() -> service.pageMessages(null, CONVERSATION_ID, 1, 20))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    /** 会话不存在 404，且不写审计。 */
    @Test
    void missingConversationShouldBeNotFound() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.pageMessages(ADMIN, CONVERSATION_ID, 1, 20))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verify(auditRecorder, never()).record(any(), any(), any(), any(), any(), any());
    }

    /** size=999 夹到 50，page=0 按 1；审计记录夹紧后的页码。 */
    @Test
    void pagingShouldBeClamped() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        when(messageMapper.selectPageAsc(CONVERSATION_ID, 0, 50)).thenReturn(List.of());
        when(messageMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        PageView<MessageView> page = service.pageMessages(ADMIN, CONVERSATION_ID, 0, 999);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(50);
        assertThat(page.items()).isEmpty();
        verify(auditRecorder).record("CHAT", String.valueOf(CONVERSATION_ID), "ADMIN_VIEW",
                null, null, "page=1 size=50 messageIds=none");
    }

    private static ChatConversation conversation() {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setScope(ChatScope.CONTRACT);
        conversation.setTaskId(10L);
        conversation.setBizId(300L);
        conversation.setEmployerId(2001L);
        conversation.setWorkerId(1001L);
        conversation.setStatus(ChatConversationStatus.OPEN);
        return conversation;
    }

    private static ChatMessage message(long id, String content) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setConversationId(CONVERSATION_ID);
        message.setSenderId(2001L);
        message.setMsgType(ChatMessageType.TEXT);
        message.setContent(content);
        message.setContactHint(0);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }
}
