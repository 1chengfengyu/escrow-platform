package com.escrow.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.common.chat.domain.ChatConversation;
import com.escrow.platform.common.chat.domain.ChatConversationStatus;
import com.escrow.platform.common.chat.domain.ChatMessage;
import com.escrow.platform.common.chat.domain.ChatMessageType;
import com.escrow.platform.common.chat.domain.ChatScope;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatConversationMapper;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatMessageMapper;
import com.escrow.platform.identity.domain.IdentityStatus;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.PlatformUser;
import com.escrow.platform.identity.domain.PlatformUserIdentity;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.identity.domain.UserStatus;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserIdentityMapper;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserMapper;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * V11 私聊真库测试的数据准备与清理（CARD-13）。
 *
 * <p>只造私聊直接依赖的行：账号、劳动者身份、任务、会话、消息。
 * 不造资金行，也不造报名 / 签约——事件接线只用到它们的 ID，
 * 测试直接把 ID 传给 {@code ChatEventDispatcher}。</p>
 *
 * <p><b>清理口径</b>：按本 fixture 自己登记的任务 / 账号 ID 精确删除，
 * 不用前缀模糊匹配，避免误删其它用例或演示数据。</p>
 *
 * @since V11
 */
final class V23ChatFixture {

    /** 账号密码哈希占位：只满足列约束，不参与任何登录。 */
    private static final String PASSWORD_HASH_PLACEHOLDER = "$2A$10$abcdefghijklmnopqrstuv";

    private final JdbcTemplate jdbcTemplate;
    private final PlatformUserMapper userMapper;
    private final PlatformUserIdentityMapper identityMapper;
    private final TaskMapper taskMapper;
    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> taskIds = new ArrayList<>();

    V23ChatFixture(DataSource dataSource, PlatformUserMapper userMapper,
                   PlatformUserIdentityMapper identityMapper, TaskMapper taskMapper,
                   ChatConversationMapper conversationMapper, ChatMessageMapper messageMapper) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.userMapper = userMapper;
        this.identityMapper = identityMapper;
        this.taskMapper = taskMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    /** 建一个账号；{@code status} 可为 {@code DISABLED} 用于测禁用路径。 */
    long user(String phone, String nickname, UserRole role, UserStatus status) {
        PlatformUser user = new PlatformUser();
        user.setPhone(phone);
        user.setEmail(phone + "@example.com");
        user.setNickname(nickname);
        user.setPasswordHash(PASSWORD_HASH_PLACEHOLDER);
        user.setRole(role);
        user.setStatus(status);
        userMapper.insert(user);
        userIds.add(user.getId());
        return user.getId();
    }

    /** 开通劳动者身份（ACTIVE），雇主发起咨询时 {@code isActiveWorker} 依赖这一行。 */
    void enableWorkerIdentity(long userId) {
        PlatformUserIdentity identity = new PlatformUserIdentity();
        identity.setUserId(userId);
        identity.setIdentityType(IdentityType.WORKER);
        identity.setStatus(IdentityStatus.ACTIVE);
        identityMapper.insert(identity);
    }

    /** 建一个「招募中」任务：已发布、未撤单、结束时间在将来。 */
    long recruitingTask(long employerId, String title) {
        Task task = new Task();
        task.setEmployerId(employerId);
        task.setTitle(title);
        task.setDescription("私聊验收用任务");
        task.setCategory("测试");
        task.setUnitPrice(10_000L);
        task.setSlotCount(1);
        task.setStartTime(LocalDateTime.now().minusDays(1));
        task.setEndTime(LocalDateTime.now().plusDays(1));
        task.setWorkLocation("线上");
        task.setPublishedAt(LocalDateTime.now().minusDays(1));
        taskMapper.insert(task);
        taskIds.add(task.getId());
        return task.getId();
    }

    /** 直接落一条会话，供权限 / 归档 / 分页用例使用。 */
    long conversation(long taskId, ChatScope scope, long bizId, long employerId, long workerId,
                      long initiatorId, ChatConversationStatus status) {
        ChatConversation conversation = new ChatConversation();
        conversation.setScope(scope);
        conversation.setTaskId(taskId);
        conversation.setBizId(bizId);
        conversation.setEmployerId(employerId);
        conversation.setWorkerId(workerId);
        conversation.setInitiatorId(initiatorId);
        conversation.setStatus(status);
        conversationMapper.insert(conversation);
        return conversation.getId();
    }

    /** 直接落一条文字消息（绕开限流与幂等，专门用于分页 / 历史数据）。 */
    long message(long conversationId, long senderId, String content, LocalDateTime createdAt) {
        return message(conversationId, senderId, content, 0, createdAt);
    }

    /** 直接落一条消息，可指定联系方式标记。 */
    long message(long conversationId, long senderId, String content, int contactHint,
                 LocalDateTime createdAt) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setSenderId(senderId);
        message.setMsgType(ChatMessageType.TEXT);
        message.setContent(content);
        message.setContactHint(contactHint);
        message.setCreatedAt(createdAt);
        messageMapper.insert(message);
        return message.getId();
    }

    /** 按登记顺序清理：消息 → 读游标 → 审计 → 会话 → 任务 → 身份 → 账号。 */
    void cleanup() {
        for (Long taskId : taskIds) {
            List<Long> conversationIds = conversationMapper.selectList(
                            new LambdaQueryWrapper<ChatConversation>()
                                    .select(ChatConversation::getId)
                                    .eq(ChatConversation::getTaskId, taskId))
                    .stream()
                    .map(ChatConversation::getId)
                    .toList();
            for (Long conversationId : conversationIds) {
                messageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId));
                jdbcTemplate.update("DELETE FROM chat_read_cursor WHERE conversation_id = ?",
                        conversationId);
                jdbcTemplate.update("DELETE FROM audit_log WHERE biz_type = 'CHAT' AND biz_no = ?",
                        String.valueOf(conversationId));
                conversationMapper.deleteById(conversationId);
            }
            taskMapper.deleteById(taskId);
        }
        for (Long userId : userIds) {
            identityMapper.delete(new LambdaQueryWrapper<PlatformUserIdentity>()
                    .eq(PlatformUserIdentity::getUserId, userId));
            userMapper.deleteById(userId);
        }
        taskIds.clear();
        userIds.clear();
    }
}
