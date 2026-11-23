package com.escrow.platform.common.chat.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.common.chat.api.MessagePageQuery;
import com.escrow.platform.common.chat.api.MessageView;
import com.escrow.platform.common.chat.api.SendMessageRequest;
import com.escrow.platform.common.chat.domain.ChatConversation;
import com.escrow.platform.common.chat.domain.ChatConversationStatus;
import com.escrow.platform.common.chat.domain.ChatMessage;
import com.escrow.platform.common.chat.domain.ChatMessageType;
import com.escrow.platform.common.chat.domain.ChatScope;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatConversationMapper;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatMessageMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.redis.RateLimiter;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.application.AccountStatusQueryService;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 消息服务单元测试（V11，FR-MSG.CHAT.02 / CHAT.03，补遗 §2.3 / §5.1~§5.3）。
 *
 * <p>只锁「守卫顺序、幂等键状态机、分页夹紧、联系方式只标记不拦截」；
 * 真实并发去重与 SQL 语义由 CARD-13 的真库用例负责。</p>
 *
 * @since V11
 */
@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

    private static final long TASK_ID = 10L;
    private static final long EMPLOYER_ID = 1001L;
    private static final long WORKER_ID = 2001L;
    private static final long CONVERSATION_ID = 9001L;
    private static final long MESSAGE_ID = 5001L;
    private static final String IDEMPOTENCY_KEY = "chat:msg:idem:" + WORKER_ID + ":" + CONVERSATION_ID + ":c-1";

    private static final LoginUser WORKER =
            new LoginUser(WORKER_ID, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Mock
    private ChatMessageMapper messageMapper;

    @Mock
    private ChatConversationMapper conversationMapper;

    @Mock
    private AccountStatusQueryService accountStatusQueryService;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private ChatMessageService messageService;

    /** {@code selectCount} 用 {@code LambdaQueryWrapper}，纯单测需手工初始化 TableInfo。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ChatMessage.class);
    }

    @BeforeEach
    void setUpRedis() {
        // 无 clientMessageId 的用例不会碰 Redis，用 lenient 避免严格桩校验误报
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ==================== send ====================

    /** 正常发送：追加 TEXT 消息并刷新会话最后消息时间。 */
    @Test
    void sendShouldAppendTextMessageAndRefreshLastMessageAt() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());
        when(accountStatusQueryService.isActive(WORKER_ID)).thenReturn(true);
        stubInsertMessage();

        MessageView view = messageService.send(WORKER, CONVERSATION_ID,
                new SendMessageRequest("  你好，明天几点到？  ", null));

        assertThat(view.id()).isEqualTo(MESSAGE_ID);
        assertThat(view.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(view.senderId()).isEqualTo(WORKER_ID);
        assertThat(view.msgType()).isEqualTo(ChatMessageType.TEXT);
        assertThat(view.content()).isEqualTo("你好，明天几点到？");
        assertThat(view.contactHint()).isFalse();
        assertThat(view.createdAt()).isNotNull();
        verify(conversationMapper).updateLastMessageAt(eq(CONVERSATION_ID), any(LocalDateTime.class));
        verify(rateLimiter).checkOrThrow(
                "escrow:rl:chat:msg:" + WORKER_ID + ":" + CONVERSATION_ID, 30, Duration.ofMinutes(1));
    }

    /** 命中手机号：只置 contact_hint=1，正文原样发送、不改写。 */
    @Test
    void sendShouldMarkContactHintWithoutRewritingContent() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());
        when(accountStatusQueryService.isActive(WORKER_ID)).thenReturn(true);
        stubInsertMessage();

        MessageView view = messageService.send(WORKER, CONVERSATION_ID,
                new SendMessageRequest("我电话 13800138000，加我", null));

        assertThat(view.contactHint()).isTrue();
        assertThat(view.content()).isEqualTo("我电话 13800138000，加我");
        verify(messageMapper).insert(any(ChatMessage.class));
    }

    /** 正文为空白 → 400，且不碰 Redis / 数据库。 */
    @Test
    void sendShouldRejectBlankContent() {
        ApiException ex = catchThrowableOfType(() -> messageService.send(WORKER, CONVERSATION_ID,
                new SendMessageRequest("   ", null)), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(conversationMapper, messageMapper, rateLimiter);
        verify(stringRedisTemplate, never()).opsForValue();
    }

    /** 正文超过 1000 字 → 400。 */
    @Test
    void sendShouldRejectTooLongContent() {
        String tooLong = "字".repeat(1001);

        ApiException ex = catchThrowableOfType(() -> messageService.send(WORKER, CONVERSATION_ID,
                new SendMessageRequest(tooLong, null)), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(conversationMapper, messageMapper);
    }

    /** 恰好 1000 字允许发送：边界值不被误杀。 */
    @Test
    void sendShouldAcceptExactlyMaxLength() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());
        when(accountStatusQueryService.isActive(WORKER_ID)).thenReturn(true);
        stubInsertMessage();

        MessageView view = messageService.send(WORKER, CONVERSATION_ID,
                new SendMessageRequest("字".repeat(1000), null));

        assertThat(view.id()).isEqualTo(MESSAGE_ID);
    }

    /** 会话不存在 → 404。 */
    @Test
    void sendShouldThrow404WhenConversationMissing() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(null);

        ApiException ex = catchThrowableOfType(() -> messageService.send(WORKER, CONVERSATION_ID,
                new SendMessageRequest("你好", null)), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(messageMapper, never()).insert(any(ChatMessage.class));
    }

    /** 非当事人 → 403（补遗 §5.1）。 */
    @Test
    void sendShouldRejectNonParty() {
        LoginUser outsider = new LoginUser(4004L, "outsider@example.com",
                UserRole.WORKER, IdentityType.WORKER);
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());

        ApiException ex = catchThrowableOfType(() -> messageService.send(outsider, CONVERSATION_ID,
                new SendMessageRequest("你好", null)), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(messageMapper, never()).insert(any(ChatMessage.class));
    }

    /** 归档会话只读 → 403。 */
    @Test
    void sendShouldRejectArchivedConversation() {
        ChatConversation archived = openConversation();
        archived.setStatus(ChatConversationStatus.ARCHIVED);
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(archived);

        ApiException ex = catchThrowableOfType(() -> messageService.send(WORKER, CONVERSATION_ID,
                new SendMessageRequest("你好", null)), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(messageMapper, never()).insert(any(ChatMessage.class));
        verifyNoInteractions(rateLimiter);
    }

    /** 账号被禁用 → 403，禁发（补遗 §5.1）。 */
    @Test
    void sendShouldRejectDisabledAccount() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());
        when(accountStatusQueryService.isActive(WORKER_ID)).thenReturn(false);

        ApiException ex = catchThrowableOfType(() -> messageService.send(WORKER, CONVERSATION_ID,
                new SendMessageRequest("你好", null)), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(messageMapper, never()).insert(any(ChatMessage.class));
    }

    /** 限流命中 → 429 原样透出，不落库。 */
    @Test
    void sendShouldPropagateRateLimit() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());
        when(accountStatusQueryService.isActive(WORKER_ID)).thenReturn(true);
        org.mockito.Mockito.doThrow(new ApiException(ErrorCode.RATE_LIMITED, "请求过于频繁"))
                .when(rateLimiter).checkOrThrow(anyString(), eq(30), any(Duration.class));

        ApiException ex = catchThrowableOfType(() -> messageService.send(WORKER, CONVERSATION_ID,
                new SendMessageRequest("你好", null)), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
        verify(messageMapper, never()).insert(any(ChatMessage.class));
    }

    /** 首次请求：抢到占位 → 落库后把键值改写为消息 ID。 */
    @Test
    void sendShouldStoreMessageIdInIdempotencyKey() {
        when(valueOperations.setIfAbsent(IDEMPOTENCY_KEY, "PENDING", Duration.ofMinutes(10)))
                .thenReturn(true);
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());
        when(accountStatusQueryService.isActive(WORKER_ID)).thenReturn(true);
        stubInsertMessage();

        messageService.send(WORKER, CONVERSATION_ID, new SendMessageRequest("你好", "c-1"));

        verify(valueOperations).set(IDEMPOTENCY_KEY, String.valueOf(MESSAGE_ID), Duration.ofMinutes(10));
    }

    /** 同 key 已完成：直接返回首次消息，不重复入库（补遗 §5.2）。 */
    @Test
    void sendShouldReplayCompletedIdempotentRequest() {
        when(valueOperations.setIfAbsent(IDEMPOTENCY_KEY, "PENDING", Duration.ofMinutes(10)))
                .thenReturn(false);
        when(valueOperations.get(IDEMPOTENCY_KEY)).thenReturn(String.valueOf(MESSAGE_ID));
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(existingMessage());

        MessageView view = messageService.send(WORKER, CONVERSATION_ID,
                new SendMessageRequest("你好", "c-1"));

        assertThat(view.id()).isEqualTo(MESSAGE_ID);
        verify(messageMapper, never()).insert(any(ChatMessage.class));
        verifyNoInteractions(conversationMapper);
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    /** 同 key 仍在处理中：409，不返回半成品。 */
    @Test
    void sendShouldConflictWhenIdempotentRequestInFlight() {
        when(valueOperations.setIfAbsent(IDEMPOTENCY_KEY, "PENDING", Duration.ofMinutes(10)))
                .thenReturn(false);
        when(valueOperations.get(IDEMPOTENCY_KEY)).thenReturn("PENDING");

        ApiException ex = catchThrowableOfType(() -> messageService.send(WORKER, CONVERSATION_ID,
                new SendMessageRequest("你好", "c-1")), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        verify(messageMapper, never()).insert(any(ChatMessage.class));
    }

    /** 抢到占位后业务失败：必须释放占位，客户端才能用同 key 重试。 */
    @Test
    void sendShouldReleaseIdempotencyKeyOnFailure() {
        when(valueOperations.setIfAbsent(IDEMPOTENCY_KEY, "PENDING", Duration.ofMinutes(10)))
                .thenReturn(true);
        ChatConversation archived = openConversation();
        archived.setStatus(ChatConversationStatus.ARCHIVED);
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(archived);

        catchThrowableOfType(() -> messageService.send(WORKER, CONVERSATION_ID,
                new SendMessageRequest("你好", "c-1")), ApiException.class);

        verify(stringRedisTemplate).delete(IDEMPOTENCY_KEY);
        verify(messageMapper, never()).insert(any(ChatMessage.class));
    }

    // ==================== page ====================

    /** 页码分页：正序返回，total 透传。 */
    @Test
    void pageShouldReturnAscendingPageWithTotal() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());
        when(messageMapper.selectPageAsc(CONVERSATION_ID, 20, 20))
                .thenReturn(List.of(message(1L, "第一条"), message(2L, "第二条")));
        when(messageMapper.selectCount(any())).thenReturn(2L);

        PageView<MessageView> view =
                messageService.page(WORKER, CONVERSATION_ID, new MessagePageQuery(2, 20, null));

        assertThat(view.page()).isEqualTo(2);
        assertThat(view.size()).isEqualTo(20);
        assertThat(view.total()).isEqualTo(2L);
        assertThat(view.items()).extracting(MessageView::content).containsExactly("第一条", "第二条");
    }

    /** size 超过 50 被夹紧，0 被抬到 1。 */
    @Test
    void pageShouldClampSize() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());
        when(messageMapper.selectPageAsc(CONVERSATION_ID, 0, 50)).thenReturn(List.of());
        when(messageMapper.selectCount(any())).thenReturn(0L);

        PageView<MessageView> view =
                messageService.page(WORKER, CONVERSATION_ID, new MessagePageQuery(1, 99, null));

        assertThat(view.size()).isEqualTo(50);
        verify(messageMapper).selectPageAsc(CONVERSATION_ID, 0, 50);
    }

    /** afterId 增量：只取新消息，limit 同样夹紧。 */
    @Test
    void pageShouldUseAfterIdIncrement() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());
        when(messageMapper.selectAfterId(CONVERSATION_ID, 100L, 50))
                .thenReturn(List.of(message(101L, "新消息")));

        PageView<MessageView> view =
                messageService.page(WORKER, CONVERSATION_ID, new MessagePageQuery(3, 99, 100L));

        assertThat(view.page()).isEqualTo(1);
        assertThat(view.size()).isEqualTo(50);
        assertThat(view.total()).isEqualTo(1L);
        assertThat(view.items()).extracting(MessageView::id).containsExactly(101L);
        verify(messageMapper, never()).selectPageAsc(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    /** 非当事人读消息 → 403，不查消息表。 */
    @Test
    void pageShouldRejectNonParty() {
        LoginUser outsider = new LoginUser(4004L, "outsider@example.com",
                UserRole.WORKER, IdentityType.WORKER);
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(openConversation());

        ApiException ex = catchThrowableOfType(() -> messageService.page(outsider, CONVERSATION_ID,
                new MessagePageQuery(1, 20, null)), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(messageMapper);
    }

    // ==================== systemMessage ====================

    /** 系统消息：sender=0、type=SYSTEM、不检测联系方式。 */
    @Test
    void systemMessageShouldAppendSystemMessage() {
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            message.setId(MESSAGE_ID);
            return 1;
        });

        Long id = messageService.systemMessage(CONVERSATION_ID, " 报名已提交 ");

        assertThat(id).isEqualTo(MESSAGE_ID);
        org.mockito.ArgumentCaptor<ChatMessage> captor =
                org.mockito.ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).insert(captor.capture());
        ChatMessage saved = captor.getValue();
        assertThat(saved.getSenderId()).isZero();
        assertThat(saved.getMsgType()).isEqualTo(ChatMessageType.SYSTEM);
        assertThat(saved.getContent()).isEqualTo("报名已提交");
        assertThat(saved.getContactHint()).isZero();
        verify(conversationMapper).updateLastMessageAt(eq(CONVERSATION_ID), any(LocalDateTime.class));
    }

    /** 系统消息正文为空 → 400。 */
    @Test
    void systemMessageShouldRejectBlankContent() {
        ApiException ex = catchThrowableOfType(
                () -> messageService.systemMessage(CONVERSATION_ID, " "), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
        verify(messageMapper, never()).insert(any(ChatMessage.class));
    }

    // ==================== 辅助 ====================

    private void stubInsertMessage() {
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            message.setId(MESSAGE_ID);
            return 1;
        });
    }

    private static ChatConversation openConversation() {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setScope(ChatScope.INQUIRY);
        conversation.setTaskId(TASK_ID);
        conversation.setBizId(0L);
        conversation.setEmployerId(EMPLOYER_ID);
        conversation.setWorkerId(WORKER_ID);
        conversation.setInitiatorId(WORKER_ID);
        conversation.setStatus(ChatConversationStatus.OPEN);
        return conversation;
    }

    private static ChatMessage existingMessage() {
        ChatMessage message = new ChatMessage();
        message.setId(MESSAGE_ID);
        message.setConversationId(CONVERSATION_ID);
        message.setSenderId(WORKER_ID);
        message.setMsgType(ChatMessageType.TEXT);
        message.setContent("你好");
        message.setContactHint(0);
        message.setCreatedAt(LocalDateTime.of(2026, 11, 21, 10, 0));
        return message;
    }

    private static ChatMessage message(Long id, String content) {
        ChatMessage message = existingMessage();
        message.setId(id);
        message.setContent(content);
        return message;
    }
}
