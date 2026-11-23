package com.escrow.platform;

import com.escrow.platform.common.chat.api.MessagePageQuery;
import com.escrow.platform.common.chat.api.MessageView;
import com.escrow.platform.common.chat.api.SendMessageRequest;
import com.escrow.platform.common.chat.application.ChatEventDispatcher;
import com.escrow.platform.common.chat.application.ChatMessageService;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务终态 → 会话归档的真库测试（V11，CARD-11，补遗 §2.2）。
 *
 * <p>必须真库的原因：归档是 {@code UPDATE ... WHERE status='OPEN'} 的条件更新，
 * 幂等与「不影响其它任务」由 SQL 语义决定，内存替身证明不了。</p>
 *
 * <p>隔离方式：每次用 {@code nanoTime} 生成互不相同的 task / 账号 ID，
 * 结束时按 {@code task_id} 清理，不触碰其它数据。</p>
 *
 * @since V11
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V23ChatArchiveIntegrationTest {

    @Autowired
    private ChatConversationMapper conversationMapper;
    @Autowired
    private ChatMessageMapper messageMapper;
    @Autowired
    private ChatMessageService chatMessageService;
    @Autowired
    private ChatEventDispatcher chatEventDispatcher;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbcTemplate;
    private long taskId;
    private long otherTaskId;
    private long employerId;
    private long workerId;
    private LoginUser worker;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        long seed = System.nanoTime() % 100_000_000L;
        taskId = 8_400_000_000L + seed;
        otherTaskId = taskId + 1;
        employerId = 8_500_000_000L + seed;
        workerId = 8_600_000_000L + seed;
        worker = new LoginUser(workerId, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);
    }

    @AfterEach
    void cleanUp() {
        for (long id : new long[]{taskId, otherTaskId}) {
            jdbcTemplate.update("DELETE FROM chat_message WHERE conversation_id IN "
                    + "(SELECT id FROM chat_conversation WHERE task_id = ?)", id);
            jdbcTemplate.update("DELETE FROM chat_read_cursor WHERE conversation_id IN "
                    + "(SELECT id FROM chat_conversation WHERE task_id = ?)", id);
            jdbcTemplate.update("DELETE FROM chat_conversation WHERE task_id = ?", id);
        }
    }

    /** 任务终态归档幂等；归档后仍可读、不能发；不碰资金表、不影响其它任务。 */
    @Test
    void archiveShouldBeIdempotentReadOnlyAndIsolated() {
        Long conversationId = insertConversation(taskId, ChatScope.CONTRACT, 88L);
        messageMapper.insert(newMessage(conversationId, "验收通过，辛苦了"));
        Long otherConversationId = insertConversation(otherTaskId, ChatScope.CONTRACT, 99L);

        int journalBefore = countOf("fund_journal_entry");
        int escrowBefore = countOf("fund_escrow_detail");

        chatEventDispatcher.archiveByTaskAfterCommit(taskId);

        assertThat(conversationMapper.selectById(conversationId).getStatus())
                .isEqualTo(ChatConversationStatus.ARCHIVED);
        assertThat(conversationMapper.selectById(otherConversationId).getStatus())
                .as("归档只影响该任务")
                .isEqualTo(ChatConversationStatus.OPEN);

        // 重复归档不报错（条件更新 0 行）
        assertThatCode(() -> chatEventDispatcher.archiveByTaskAfterCommit(taskId))
                .doesNotThrowAnyException();

        // 归档会话仍可分页读取
        PageView<MessageView> page = chatMessageService.page(worker, conversationId,
                new MessagePageQuery(1, 20, null));
        assertThat(page.items()).extracting(MessageView::content)
                .containsExactly("验收通过，辛苦了");

        // 归档后再发消息 403
        assertThatThrownBy(() -> chatMessageService.send(worker, conversationId,
                new SendMessageRequest("还能发吗", null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        // 归档不改变任何资金数据
        assertThat(countOf("fund_journal_entry")).isEqualTo(journalBefore);
        assertThat(countOf("fund_escrow_detail")).isEqualTo(escrowBefore);
    }

    private Long insertConversation(long task, ChatScope scope, long bizId) {
        ChatConversation conversation = new ChatConversation();
        conversation.setScope(scope);
        conversation.setTaskId(task);
        conversation.setBizId(bizId);
        conversation.setEmployerId(employerId);
        conversation.setWorkerId(workerId);
        conversation.setInitiatorId(employerId);
        conversation.setStatus(ChatConversationStatus.OPEN);
        conversationMapper.insert(conversation);
        return conversation.getId();
    }

    private ChatMessage newMessage(Long conversationId, String content) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setSenderId(employerId);
        message.setMsgType(ChatMessageType.TEXT);
        message.setContent(content);
        message.setContactHint(0);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private int countOf(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
