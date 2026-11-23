package com.escrow.platform.common.chat.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.common.chat.api.MessagePageQuery;
import com.escrow.platform.common.chat.api.MessageView;
import com.escrow.platform.common.chat.api.SendMessageRequest;
import com.escrow.platform.common.chat.domain.ChatConversation;
import com.escrow.platform.common.chat.domain.ChatConversationStatus;
import com.escrow.platform.common.chat.domain.ChatMessage;
import com.escrow.platform.common.chat.domain.ChatMessageType;
import com.escrow.platform.common.chat.domain.ContactDetector;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatConversationMapper;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatMessageMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.redis.RateLimiter;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.application.AccountStatusQueryService;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 消息服务（V11，FR-MSG.CHAT.02 / CHAT.03，补遗 §2.3 / §4 / §5.1~§5.3）。
 *
 * <p><b>只追加</b>：没有编辑、撤回、删除入口；消息一旦落库即为证据。</p>
 *
 * <p><b>幂等放 Redis</b>：key 为
 * {@code chat:msg:idem:{userId}:{conversationId}:{clientMessageId}}，TTL 10 分钟。
 * 不新增 DDL 列——网关超时重试时同 key 直接返回首次的消息，不重复入库。
 * 键值先写占位符 {@code PENDING}，落库成功后改写为消息 ID。</p>
 *
 * <p><b>联系方式只标记不拦截</b>：命中只把 {@code contact_hint} 置 1，正文原样发送，
 * 日志只记标记位、不记正文（补遗 §5.3，NFR-SEC.04）。</p>
 *
 * @since V11
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    /** 正文上限：按 UTF-16 计数，与 {@code VARCHAR(2000)} 列容量（1000 汉字）一致。 */
    private static final int MAX_CONTENT_LENGTH = 1000;

    /** 单页 / 单次增量的条数上限（补遗 §2.3）。 */
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final int SEND_LIMIT_PER_MINUTE = 30;
    private static final Duration SEND_WINDOW = Duration.ofMinutes(1);

    /** 幂等键 TTL：10 分钟，覆盖客户端重试窗口。 */
    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(10);
    private static final String IDEMPOTENCY_KEY_PREFIX = "chat:msg:idem:";
    private static final String PENDING = "PENDING";
    private static final String RATE_LIMIT_KEY_PREFIX = "escrow:rl:chat:msg:";

    private static final long SYSTEM_SENDER_ID = 0L;

    /** 单次取证的证据消息上限（CARD-12）：一条演示取证请求足够，也避免一次拷爆证据表。 */
    private static final int MAX_EVIDENCE_MESSAGES = 20;
    private static final String EVIDENCE_NOT_IN_CONVERSATION = "消息不属于该争议关联的会话";

    private static final String UNAUTHENTICATED_MESSAGE = "请先登录";
    private static final String FORBIDDEN_MESSAGE = "无权访问该会话";
    private static final String NOT_FOUND_MESSAGE = "会话不存在";

    private final ChatMessageMapper messageMapper;
    private final ChatConversationMapper conversationMapper;
    private final AccountStatusQueryService accountStatusQueryService;
    private final RateLimiter rateLimiter;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 发送一条文字消息（FR-MSG.CHAT.02）。
     *
     * <p>守卫顺序：登录 → 正文 → 幂等占位 → 会话当事人 → 归档只读 → 账号状态 → 限流 →
     * 联系方式检测 → 追加消息 → 刷新最后消息时间。任一步失败都会释放幂等占位，
     * 让客户端用同 key 重试时不至于被 10 分钟的占位卡住。</p>
     *
     * @param current        会话主体
     * @param conversationId 会话 ID
     * @param request        正文与客户端消息 ID
     * @return 已落库的消息视图
     * @throws ApiException 401 未登录 / 400 正文为空或超长 / 404 会话不存在 /
     *                      403 非当事人、会话已归档、账号被禁用 / 409 同 key 正在处理 / 429 发送过频
     */
    @Transactional
    public MessageView send(LoginUser current, Long conversationId, SendMessageRequest request) {
        Long userId = requireLogin(current);
        String content = requireContent(request);
        String idempotencyKey = idempotencyKey(userId, conversationId, request);
        if (idempotencyKey != null) {
            MessageView replay = acquireIdempotency(idempotencyKey);
            if (replay != null) {
                log.info("消息幂等命中 conversationId={} userId={} messageId={}",
                        conversationId, userId, replay.id());
                return replay;
            }
        }
        try {
            ChatConversation conversation = loadConversationForParty(conversationId, userId);
            if (conversation.getStatus() != ChatConversationStatus.OPEN) {
                throw new ApiException(ErrorCode.FORBIDDEN, "会话已归档，只能查看历史消息");
            }
            if (!accountStatusQueryService.isActive(userId)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "账号已被禁用，不能发送消息");
            }
            rateLimiter.checkOrThrow(RATE_LIMIT_KEY_PREFIX + userId + ":" + conversationId,
                    SEND_LIMIT_PER_MINUTE, SEND_WINDOW);

            boolean contactHint = ContactDetector.hit(content);
            LocalDateTime now = LocalDateTime.now();
            ChatMessage message = new ChatMessage();
            message.setConversationId(conversationId);
            message.setSenderId(userId);
            message.setMsgType(ChatMessageType.TEXT);
            message.setContent(content);
            message.setContactHint(contactHint ? 1 : 0);
            message.setCreatedAt(now);
            messageMapper.insert(message);
            conversationMapper.updateLastMessageAt(conversationId, now);
            storeIdempotencyResult(idempotencyKey, message.getId());
            // 不记正文：只记会话 / 消息 / 发送人 / 命中标记（NFR-SEC.04）
            log.info("消息发送 conversationId={} messageId={} senderId={} contactHint={}",
                    conversationId, message.getId(), userId, contactHint);
            return toView(message);
        } catch (RuntimeException ex) {
            releaseIdempotency(idempotencyKey);
            throw ex;
        }
    }

    /**
     * 历史消息：默认页码分页，带 {@code afterId} 时走增量（FR-MSG.CHAT.02）。
     *
     * @param current        会话主体
     * @param conversationId 会话 ID
     * @param query          页码 / 页大小 / 增量游标
     * @return 正序消息分页视图
     * @throws ApiException 401 未登录 / 404 会话不存在 / 403 非当事人
     */
    public PageView<MessageView> page(LoginUser current, Long conversationId, MessagePageQuery query) {
        Long userId = requireLogin(current);
        loadConversationForParty(conversationId, userId);

        int size = clampSize(query == null ? null : query.size());
        if (query != null && query.afterId() != null) {
            List<MessageView> items = messageMapper
                    .selectAfterId(conversationId, query.afterId(), size)
                    .stream()
                    .map(ChatMessageService::toView)
                    .toList();
            // 增量结果没有「总页数」语义：page 恒为 1，total 取本次返回条数
            return PageView.of(items, 1, size, items.size());
        }

        int page = Math.max(1, query == null || query.page() == null ? 1 : query.page());
        List<MessageView> items = messageMapper.selectPageAsc(conversationId, (page - 1) * size, size)
                .stream()
                .map(ChatMessageService::toView)
                .toList();
        long total = messageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId));
        return PageView.of(items, page, size, total);
    }

    /**
     * 追加系统消息（CARD-10 事件调用）：{@code sender_id = 0}，不受限流与幂等键约束。
     *
     * <p>幂等由调用方的事件表保证：同一业务事件只派发一次。</p>
     *
     * @param conversationId 会话 ID
     * @param content        系统提示正文，1~1000 字
     * @return 新消息 ID
     * @throws ApiException 400 参数为空或超长
     */
    @Transactional
    public Long systemMessage(Long conversationId, String content) {
        if (conversationId == null) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "conversationId 不能为空");
        }
        String text = normalizeContent(content);
        LocalDateTime now = LocalDateTime.now();
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setSenderId(SYSTEM_SENDER_ID);
        message.setMsgType(ChatMessageType.SYSTEM);
        message.setContent(text);
        message.setContactHint(0);
        message.setCreatedAt(now);
        messageMapper.insert(message);
        conversationMapper.updateLastMessageAt(conversationId, now);
        log.info("系统消息 conversationId={} messageId={}", conversationId, message.getId());
        return message.getId();
    }

    // ==================== 内部 ====================

    /**
     * 争议取证读取（CARD-12，补遗 §5.4）：按消息 ID 读取，并校验消息属于该任务该对双方的会话。
     *
     * <p>咨询 / 报名 / 签约三类会话取并集：同一任务、同一雇主与劳动者之间的任何会话都可作为证据，
     * 覆盖从咨询到达成履约的全过程。</p>
     *
     * <p>只读：不写 {@code chat_message}、不改会话状态，取证落库由调用方（争议域）完成。</p>
     *
     * @param taskId     任务 ID
     * @param employerId 雇主账号 ID
     * @param workerId   劳动者账号 ID
     * @param messageIds 待取证的消息 ID，1~20 条
     * @return 按消息 ID 升序的消息视图
     * @throws ApiException 400 参数为空、超过上限或消息不属于该争议关联的会话
     */
    public List<MessageView> messagesOfTaskPair(Long taskId, Long employerId, Long workerId,
                                                List<Long> messageIds) {
        if (taskId == null || employerId == null || workerId == null) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "取证参数不完整");
        }
        if (messageIds == null || messageIds.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "messageIds 不能为空");
        }
        if (messageIds.size() > MAX_EVIDENCE_MESSAGES) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER,
                    "messageIds 最多 " + MAX_EVIDENCE_MESSAGES + " 条");
        }
        List<Long> ids = messageIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (ids.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "messageIds 不能为空");
        }

        List<Long> conversationIds = conversationMapper.selectList(
                        new LambdaQueryWrapper<ChatConversation>()
                                .select(ChatConversation::getId)
                                .eq(ChatConversation::getTaskId, taskId)
                                .eq(ChatConversation::getEmployerId, employerId)
                                .eq(ChatConversation::getWorkerId, workerId))
                .stream()
                .map(ChatConversation::getId)
                .toList();
        List<ChatMessage> rows = conversationIds.isEmpty()
                ? List.of()
                : messageMapper.selectBatchIds(ids);
        if (rows.size() != ids.size()
                || rows.stream().anyMatch(row -> !conversationIds.contains(row.getConversationId()))) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, EVIDENCE_NOT_IN_CONVERSATION);
        }
        return rows.stream()
                .sorted(Comparator.comparing(ChatMessage::getId))
                .map(ChatMessageService::toView)
                .toList();
    }

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
            log.warn("越权访问消息 conversationId={} userId={}", conversationId, userId);
            throw new ApiException(ErrorCode.FORBIDDEN, FORBIDDEN_MESSAGE);
        }
        return conversation;
    }

    private static String requireContent(SendMessageRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "消息正文不能为空");
        }
        return normalizeContent(request.content());
    }

    private static String normalizeContent(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "消息正文不能为空");
        }
        // 按 UTF-16 计数，与 VARCHAR(2000) 的字节容量边界一致
        if (trimmed.length() > MAX_CONTENT_LENGTH) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "消息正文不能超过 1000 字");
        }
        return trimmed;
    }

    private static int clampSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.max(1, Math.min(MAX_PAGE_SIZE, size));
    }

    private static String idempotencyKey(Long userId, Long conversationId, SendMessageRequest request) {
        String clientMessageId = request.clientMessageId();
        if (clientMessageId == null || clientMessageId.isBlank()) {
            return null;
        }
        return IDEMPOTENCY_KEY_PREFIX + userId + ":" + conversationId + ":" + clientMessageId.trim();
    }

    /**
     * 抢占幂等键。
     *
     * @return 命中已完成的同 key 请求时返回首次消息；本请求抢到占位返回 {@code null}
     */
    private MessageView acquireIdempotency(String key) {
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(key, PENDING, IDEMPOTENCY_TTL);
        if (Boolean.TRUE.equals(acquired)) {
            return null;
        }
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null) {
            // 键在读之前刚好过期：重新抢一次，抢不到说明有人并发处理
            Boolean retried = stringRedisTemplate.opsForValue().setIfAbsent(key, PENDING, IDEMPOTENCY_TTL);
            if (Boolean.TRUE.equals(retried)) {
                return null;
            }
            throw new ApiException(ErrorCode.CONFLICT, "相同请求正在处理中，请稍后重试");
        }
        if (PENDING.equals(value)) {
            // 首次请求尚未落库完成：不返回半成品，让客户端稍后重试
            throw new ApiException(ErrorCode.CONFLICT, "相同请求正在处理中，请稍后重试");
        }
        Long messageId = parseMessageId(value);
        ChatMessage existing = messageId == null ? null : messageMapper.selectById(messageId);
        if (existing != null) {
            return toView(existing);
        }
        // 值失效（消息已不存在）：释放后按新请求处理
        stringRedisTemplate.delete(key);
        Boolean reacquired = stringRedisTemplate.opsForValue().setIfAbsent(key, PENDING, IDEMPOTENCY_TTL);
        if (!Boolean.TRUE.equals(reacquired)) {
            throw new ApiException(ErrorCode.CONFLICT, "相同请求正在处理中，请稍后重试");
        }
        return null;
    }

    private void storeIdempotencyResult(String key, Long messageId) {
        if (key == null) {
            return;
        }
        stringRedisTemplate.opsForValue().set(key, String.valueOf(messageId), IDEMPOTENCY_TTL);
    }

    private void releaseIdempotency(String key) {
        if (key == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(key);
        } catch (RuntimeException redisFailure) {
            // 释放失败不能覆盖真正的业务异常；10 分钟后 TTL 会自愈
            log.warn("幂等键释放失败 key={}", key, redisFailure);
        }
    }

    private static Long parseMessageId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static MessageView toView(ChatMessage message) {
        return new MessageView(message.getId(), message.getConversationId(), message.getSenderId(),
                message.getMsgType(), message.getContent(),
                Integer.valueOf(1).equals(message.getContactHint()), message.getCreatedAt());
    }

    private static Long requireLogin(LoginUser current) {
        if (current == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_MESSAGE);
        }
        return current.userId();
    }
}
