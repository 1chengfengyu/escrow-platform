package com.escrow.platform;

import com.escrow.platform.common.chat.domain.ChatConversation;
import com.escrow.platform.common.chat.domain.ChatConversationStatus;
import com.escrow.platform.common.chat.domain.ChatMessage;
import com.escrow.platform.common.chat.domain.ChatMessageType;
import com.escrow.platform.common.chat.domain.ChatScope;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatConversationMapper;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatMessageMapper;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatReadCursorMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V23 三张聊天表的真库测试（V11，CARD-03）。
 *
 * <p>必须真库的原因：正确性完全依赖数据库语义——唯一键去重、CHECK 拦截、
 * {@code LIMIT/OFFSET} 分页、{@code ON DUPLICATE KEY UPDATE + GREATEST} 的游标单调性。
 * 用内存替身测，测的是自己写的假实现（docs/verification.md 第 3 节）。</p>
 *
 * <p>隔离方式：每次用 {@code nanoTime} 生成互不相同的 task / 账号 ID，
 * 结束时按 {@code task_id} 清理，不触碰其它数据。</p>
 *
 * @since V11
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V23ChatPersistenceIntegrationTest {

    @Autowired
    private ChatConversationMapper conversationMapper;
    @Autowired
    private ChatMessageMapper messageMapper;
    @Autowired
    private ChatReadCursorMapper readCursorMapper;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbcTemplate;
    private long taskId;
    private long employerId;
    private long workerId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        long seed = System.nanoTime() % 100_000_000L;
        taskId = 8_100_000_000L + seed;
        employerId = 8_200_000_000L + seed;
        workerId = 8_300_000_000L + seed;
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM chat_message WHERE conversation_id IN "
                + "(SELECT id FROM chat_conversation WHERE task_id = ?)", taskId);
        jdbcTemplate.update("DELETE FROM chat_read_cursor WHERE conversation_id IN "
                + "(SELECT id FROM chat_conversation WHERE task_id = ?)", taskId);
        jdbcTemplate.update("DELETE FROM chat_conversation WHERE task_id = ?", taskId);
    }

    /** 迁移已推进到 23：三张表由 Flyway 建出，不是测试代码里手工建的。 */
    @Test
    void flywayShouldApplyV23() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '23' AND success = 1",
                Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    /** 唯一键回查命中既有会话；重复插入同键被唯一索引拒绝。 */
    @Test
    void uniqueKeyShouldReturnExistingAndRejectDuplicate() {
        ChatConversation created = newConversation(ChatScope.INQUIRY, 0L);
        conversationMapper.insert(created);

        ChatConversation found = conversationMapper.selectByUniqueKey(
                ChatScope.INQUIRY, taskId, 0L, employerId, workerId);
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(created.getId());
        assertThat(found.getScope()).isEqualTo(ChatScope.INQUIRY);
        assertThat(found.getStatus()).isEqualTo(ChatConversationStatus.OPEN);

        assertThatThrownBy(() -> conversationMapper.insert(newConversation(ChatScope.INQUIRY, 0L)))
                .as("同 (scope, task, biz, 双方) 只能有一条会话")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** CHECK 兜底：非法 scope 与非法 contact_hint 在数据库层被拒。 */
    @Test
    void checkConstraintsShouldRejectInvalidEnums() {
        // MySQL 的 CHECK 违例是 error 3819，Spring 未映射成 DataIntegrityViolationException，
        // 因此断言落在 DataAccessException + 约束名上，避免把「哪个异常」当成被测行为。
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO chat_conversation (id, scope, task_id, biz_id, employer_id, worker_id,"
                        + " initiator_id, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                taskId + 1, "INVALID", taskId, 0L, employerId, workerId, employerId, "OPEN"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_chat_conv_scope");

        ChatConversation conversation = newConversation(ChatScope.CONTRACT, 42L);
        conversationMapper.insert(conversation);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO chat_message (id, conversation_id, sender_id, msg_type, content, contact_hint)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                taskId + 2, conversation.getId(), workerId, "TEXT", "联系我", 2))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_chat_msg_hint");
    }

    /** 正序分页与 afterId 增量；afterId 超过最大 ID 返回空。 */
    @Test
    void messagePagingShouldBeAscendingAndIncremental() {
        Long conversationId = insertConversation(ChatScope.INQUIRY, 0L);
        LocalDateTime base = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        for (int i = 1; i <= 5; i++) {
            messageMapper.insert(newMessage(conversationId, workerId, "第" + i + "条",
                    base.plusSeconds(i)));
        }

        List<ChatMessage> firstPage = messageMapper.selectPageAsc(conversationId, 0, 2);
        assertThat(firstPage).extracting(ChatMessage::getContent)
                .containsExactly("第1条", "第2条");
        assertThat(firstPage.get(0).getMsgType()).isEqualTo(ChatMessageType.TEXT);

        List<ChatMessage> lastPage = messageMapper.selectPageAsc(conversationId, 4, 10);
        assertThat(lastPage).extracting(ChatMessage::getContent).containsExactly("第5条");

        List<ChatMessage> incremental =
                messageMapper.selectAfterId(conversationId, firstPage.get(1).getId(), 50);
        assertThat(incremental).extracting(ChatMessage::getContent)
                .containsExactly("第3条", "第4条", "第5条");
        assertThat(messageMapper.selectAfterId(conversationId, Long.MAX_VALUE, 50)).isEmpty();
        assertThat(messageMapper.selectMaxId(conversationId))
                .isEqualTo(incremental.get(incremental.size() - 1).getId());
    }

    /** 读游标连续 upsert 不报错，且 GREATEST 保证只前进不回退。 */
    @Test
    void readCursorUpsertShouldTakeGreater() {
        Long conversationId = insertConversation(ChatScope.INQUIRY, 0L);
        assertThat(readCursorMapper.selectLastReadMessageId(conversationId, workerId)).isNull();

        readCursorMapper.upsert(conversationId, workerId, 10L);
        readCursorMapper.upsert(conversationId, workerId, 5L);
        assertThat(readCursorMapper.selectLastReadMessageId(conversationId, workerId))
                .isEqualTo(10L);

        readCursorMapper.upsert(conversationId, workerId, 20L);
        assertThat(readCursorMapper.selectLastReadMessageId(conversationId, workerId))
                .isEqualTo(20L);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_read_cursor WHERE conversation_id = ? AND user_id = ?",
                Integer.class, conversationId, workerId);
        assertThat(rows).as("复合主键只允许一行").isEqualTo(1);
        assertThat(readCursorMapper.selectLastReadMessageId(conversationId, employerId)).isNull();
    }

    /** 未读只算对方消息；标记已读后单会话与总数同时归零。 */
    @Test
    void unreadShouldExcludeOwnMessagesAndClearAfterRead() {
        Long conversationId = insertConversation(ChatScope.CONTRACT, 42L);
        LocalDateTime base = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        messageMapper.insert(newMessage(conversationId, workerId, "第一条", base.plusSeconds(1)));
        messageMapper.insert(newMessage(conversationId, workerId, "第二条", base.plusSeconds(2)));
        messageMapper.insert(newMessage(conversationId, employerId, "回复", base.plusSeconds(3)));

        assertThat(messageMapper.countUnread(conversationId, employerId)).isEqualTo(2L);
        assertThat(messageMapper.countUnread(conversationId, workerId)).isEqualTo(1L);
        assertThat(messageMapper.countUnreadTotal(employerId, true)).isEqualTo(2L);
        assertThat(messageMapper.countUnreadTotal(workerId, false)).isEqualTo(1L);

        readCursorMapper.upsert(conversationId, employerId, messageMapper.selectMaxId(conversationId));
        assertThat(messageMapper.countUnread(conversationId, employerId)).isZero();
        assertThat(messageMapper.countUnreadTotal(employerId, true)).isZero();
    }

    /** 列表按身份过滤、可选 scope、按最后消息时间倒序。 */
    @Test
    void conversationListShouldFilterByIdentityAndScope() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        ChatConversation inquiry = newConversation(ChatScope.INQUIRY, 0L);
        inquiry.setLastMessageAt(now);
        conversationMapper.insert(inquiry);

        ChatConversation application = newConversation(ChatScope.APPLICATION, 777L);
        application.setLastMessageAt(now.plusSeconds(5));
        conversationMapper.insert(application);

        List<ChatConversation> employerList =
                conversationMapper.selectPageByIdentity(employerId, true, null, 0, 50);
        assertThat(employerList).extracting(ChatConversation::getScope)
                .containsExactly(ChatScope.APPLICATION, ChatScope.INQUIRY);

        List<ChatConversation> workerInquiry =
                conversationMapper.selectPageByIdentity(workerId, false, ChatScope.INQUIRY, 0, 50);
        assertThat(workerInquiry).extracting(ChatConversation::getId)
                .containsExactly(inquiry.getId());
        assertThat(conversationMapper.selectPageByIdentity(workerId, false, ChatScope.CONTRACT, 0, 50))
                .isEmpty();

        assertThat(conversationMapper.countByIdentity(employerId, true, null)).isEqualTo(2L);
        assertThat(conversationMapper.countByIdentity(workerId, false, ChatScope.INQUIRY))
                .isEqualTo(1L);
    }

    /** 任务终态归档幂等，归档后仍可读。 */
    @Test
    void archiveByTaskShouldBeIdempotentAndStillReadable() {
        Long inquiryId = insertConversation(ChatScope.INQUIRY, 0L);
        Long contractId = insertConversation(ChatScope.CONTRACT, 88L);

        assertThat(conversationMapper.archiveByTaskId(taskId)).isEqualTo(2);
        assertThat(conversationMapper.archiveByTaskId(taskId)).as("重复归档不重复计数").isZero();

        assertThat(conversationMapper.selectById(inquiryId).getStatus())
                .isEqualTo(ChatConversationStatus.ARCHIVED);
        assertThat(conversationMapper.selectById(contractId).getStatus())
                .isEqualTo(ChatConversationStatus.ARCHIVED);
        assertThat(conversationMapper.selectByUniqueKey(ChatScope.CONTRACT, taskId, 88L,
                employerId, workerId)).isNotNull();
    }

    /** 不存在的会话/消息一律返回空集合或 null，不抛异常。 */
    @Test
    void unknownIdsShouldReturnEmpty() {
        long unknown = taskId + 9_999;
        assertThat(conversationMapper.selectPageByIdentity(unknown, true, null, 0, 50)).isEmpty();
        assertThat(conversationMapper.countByIdentity(unknown, true, null)).isZero();
        assertThat(conversationMapper.selectByUniqueKey(ChatScope.INQUIRY, unknown, 0L,
                unknown, unknown)).isNull();
        assertThat(messageMapper.selectPageAsc(unknown, 0, 10)).isEmpty();
        assertThat(messageMapper.selectAfterId(unknown, 0L, 10)).isEmpty();
        assertThat(messageMapper.countUnread(unknown, unknown)).isZero();
        assertThat(messageMapper.selectMaxId(unknown)).isNull();
    }

    private ChatConversation newConversation(ChatScope scope, long bizId) {
        ChatConversation conversation = new ChatConversation();
        conversation.setScope(scope);
        conversation.setTaskId(taskId);
        conversation.setBizId(bizId);
        conversation.setEmployerId(employerId);
        conversation.setWorkerId(workerId);
        conversation.setInitiatorId(workerId);
        conversation.setStatus(ChatConversationStatus.OPEN);
        return conversation;
    }

    private Long insertConversation(ChatScope scope, long bizId) {
        ChatConversation conversation = newConversation(scope, bizId);
        conversationMapper.insert(conversation);
        return conversation.getId();
    }

    private ChatMessage newMessage(Long conversationId, Long senderId, String content,
                                   LocalDateTime createdAt) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setSenderId(senderId);
        message.setMsgType(ChatMessageType.TEXT);
        message.setContent(content);
        message.setContactHint(0);
        message.setCreatedAt(createdAt);
        return message;
    }
}
