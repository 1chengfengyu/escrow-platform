package com.escrow.platform;

import com.escrow.platform.common.chat.api.ConversationView;
import com.escrow.platform.common.chat.api.CreateConversationRequest;
import com.escrow.platform.common.chat.api.MessagePageQuery;
import com.escrow.platform.common.chat.api.MessageView;
import com.escrow.platform.common.chat.api.SendMessageRequest;
import com.escrow.platform.common.chat.application.ChatAdminQueryService;
import com.escrow.platform.common.chat.application.ChatConversationService;
import com.escrow.platform.common.chat.application.ChatMessageService;
import com.escrow.platform.common.chat.application.ChatUnreadService;
import com.escrow.platform.common.chat.domain.ChatConversationStatus;
import com.escrow.platform.common.chat.domain.ChatScope;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatConversationMapper;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatMessageMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.identity.domain.UserStatus;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserIdentityMapper;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserMapper;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 私聊权限与失败路径真库验收（V11，CARD-13，补遗 §9 第 4、8、10 条）。
 *
 * <p>覆盖：非当事人 403 + 审计、管理端只读取证、归档只读、参数与状态守卫。
 * 断言直接读 {@code audit_log}，因为「记了审计」是补遗 §5.1 / §5.3 的硬要求，
 * 只断言异常码证明不了。</p>
 *
 * @since V11
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V23ChatPermissionIntegrationTest {

    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    @Autowired
    private ChatConversationService conversationService;
    @Autowired
    private ChatMessageService messageService;
    @Autowired
    private ChatUnreadService unreadService;
    @Autowired
    private ChatAdminQueryService adminQueryService;
    @Autowired
    private ChatConversationMapper conversationMapper;
    @Autowired
    private ChatMessageMapper chatMessageMapper;
    @Autowired
    private PlatformUserMapper platformUserMapper;
    @Autowired
    private PlatformUserIdentityMapper platformUserIdentityMapper;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbcTemplate;
    private V23ChatFixture fixture;

    private long employerId;
    private long workerId;
    private long taskId;

    private LoginUser employer;
    private LoginUser worker;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        fixture = new V23ChatFixture(dataSource, platformUserMapper, platformUserIdentityMapper,
                taskMapper, conversationMapper, chatMessageMapper);
        String employerPhone = nextPhone();
        String workerPhone = nextPhone();
        employerId = fixture.user(employerPhone, "艾雇主", UserRole.EMPLOYER, UserStatus.ACTIVE);
        workerId = fixture.user(workerPhone, "卫劳动者", UserRole.WORKER, UserStatus.ACTIVE);
        fixture.enableWorkerIdentity(workerId);
        taskId = fixture.recruitingTask(employerId, "权限验收任务");
        employer = new LoginUser(employerId, employerPhone, UserRole.EMPLOYER, IdentityType.EMPLOYER);
        worker = new LoginUser(workerId, workerPhone, UserRole.WORKER, IdentityType.WORKER);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    /** 补遗 §9-4：非当事人看详情 / 拉消息 / 读未读 / 标已读一律 403，并写审计。 */
    @Test
    void frMsgChat02NonPartyShouldGet403AndBeAudited() {
        long conversationId = openConversation();
        long outsiderId = fixture.user(nextPhone(), "路人", UserRole.WORKER, UserStatus.ACTIVE);
        LoginUser outsider = new LoginUser(outsiderId, nextPhone(), UserRole.WORKER, IdentityType.WORKER);

        assertForbidden(() -> conversationService.detail(outsider, conversationId));
        assertForbidden(() -> messageService.page(outsider, conversationId,
                new MessagePageQuery(1, 20, null)));
        assertForbidden(() -> messageService.send(outsider, conversationId,
                new SendMessageRequest("我不是当事人", null)));
        assertForbidden(() -> unreadService.unreadOf(outsider, conversationId));
        assertForbidden(() -> unreadService.markRead(outsider, conversationId));

        assertThat(auditCount("FORBIDDEN_VIEW", conversationId))
                .as("越权看详情写 FORBIDDEN_VIEW 审计").isGreaterThanOrEqualTo(1);
        assertThat(auditCount("FORBIDDEN_READ", conversationId))
                .as("越权读未读写 FORBIDDEN_READ 审计").isGreaterThanOrEqualTo(1);
    }

    /** 补遗 §9-8：归档会话只读——可读历史、可标已读，不能发消息。 */
    @Test
    void frMsgChat02ArchivedConversationShouldBeReadOnly() {
        long conversationId = fixture.conversation(taskId, ChatScope.CONTRACT, 9_300_001L,
                employerId, workerId, employerId, ChatConversationStatus.ARCHIVED);
        fixture.message(conversationId, workerId, "交付物请查收", LocalDateTime.now());

        assertThatThrownBy(() -> messageService.send(worker, conversationId,
                new SendMessageRequest("归档后还能发吗", null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        PageView<MessageView> page = messageService.page(employer, conversationId,
                new MessagePageQuery(1, 20, null));
        assertThat(page.items()).extracting(MessageView::content).containsExactly("交付物请查收");
        assertThatCode(() -> unreadService.markRead(employer, conversationId))
                .doesNotThrowAnyException();
    }

    /** 补遗 §9-8 / §5.3：管理端只读取证，写 ADMIN_VIEW 审计但不落正文；非管理员 403。 */
    @Test
    void frMsgChat02AdminShouldReadOnlyWithAuditWithoutContent() {
        long conversationId = openConversation();
        String sensitive = "交付地址在城西仓库";
        messageService.send(worker, conversationId, new SendMessageRequest(sensitive, null));

        LoginUser admin = new LoginUser(employerId, nextPhone(), UserRole.ADMIN, IdentityType.ADMIN);
        PageView<MessageView> page = adminQueryService.pageMessages(admin, conversationId, 1, 20);
        assertThat(page.items()).extracting(MessageView::content).containsExactly(sensitive);

        assertThat(auditCount("ADMIN_VIEW", conversationId)).isEqualTo(1);
        String detail = latestAuditDetail("ADMIN_VIEW", conversationId);
        assertThat(detail).contains("page=1").contains("messageIds=").doesNotContain(sensitive);

        assertForbidden(() -> adminQueryService.pageMessages(employer, conversationId, 1, 20));
        assertThatThrownBy(() -> adminQueryService.pageMessages(null, conversationId, 1, 20))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    /** 补遗 §9-10：空 / 纯空白 / 超长正文 400，会话不存在 404，未登录 401。 */
    @Test
    void frMsgChat02InvalidMessageRequestsShouldBeRejected() {
        long conversationId = openConversation();

        assertInvalid(() -> messageService.send(worker, conversationId,
                new SendMessageRequest("   ", null)));
        assertThatThrownBy(() -> messageService.send(worker, conversationId,
                new SendMessageRequest("字".repeat(1001), null)))
                .isInstanceOf(ApiException.class);
        assertThatCode(() -> messageService.send(worker, conversationId,
                new SendMessageRequest("。", null)))
                .as("单字边界应通过").doesNotThrowAnyException();
        assertThatThrownBy(() -> messageService.send(worker, 9_999_999_999L,
                new SendMessageRequest("会话不存在", null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        assertThatThrownBy(() -> messageService.send(null, conversationId,
                new SendMessageRequest("未登录", null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThatThrownBy(() -> conversationService.detail(null, conversationId))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    /** 补遗 §9-10：草稿任务 404、已撤单 403、自己对自己 403、非 INQUIRY scope 400。 */
    @Test
    void frMsgChat01InquiryGuardsShouldRejectInvalidTargets() {
        long draftTaskId = fixture.recruitingTask(employerId, "草稿任务");
        jdbcTemplate.update("UPDATE task SET published_at = NULL WHERE id = ?", draftTaskId);
        assertThatThrownBy(() -> conversationService.createInquiry(worker,
                new CreateConversationRequest(draftTaskId, ChatScope.INQUIRY, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        long cancelledTaskId = fixture.recruitingTask(employerId, "已撤单任务");
        jdbcTemplate.update("UPDATE task SET cancelled_at = NOW(3) WHERE id = ?", cancelledTaskId);
        assertThatThrownBy(() -> conversationService.createInquiry(worker,
                new CreateConversationRequest(cancelledTaskId, ChatScope.INQUIRY, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        assertThatThrownBy(() -> conversationService.createInquiry(employer,
                new CreateConversationRequest(taskId, ChatScope.INQUIRY, employerId)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        assertInvalid(() -> conversationService.createInquiry(worker,
                new CreateConversationRequest(taskId, ChatScope.CONTRACT, null)));
        assertInvalid(() -> conversationService.createInquiry(worker,
                new CreateConversationRequest(null, ChatScope.INQUIRY, null)));
        assertInvalid(() -> conversationService.createInquiry(employer,
                new CreateConversationRequest(taskId, ChatScope.INQUIRY, null)));
    }

    // ==================== 辅助 ====================

    private long openConversation() {
        return conversationService.createInquiry(worker,
                new CreateConversationRequest(taskId, ChatScope.INQUIRY, null)).id();
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    private int auditCount(String action, long conversationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE biz_type = 'CHAT' AND biz_no = ? AND action = ?",
                Integer.class, String.valueOf(conversationId), action);
        return count == null ? 0 : count;
    }

    private String latestAuditDetail(String action, long conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT detail FROM audit_log WHERE biz_type = 'CHAT' AND biz_no = ? AND action = ?"
                        + " ORDER BY created_at DESC, id DESC LIMIT 1",
                String.class, String.valueOf(conversationId), action);
    }

    private static String nextPhone() {
        return "13" + String.format("%09d", PHONE_SEQ.incrementAndGet() % 1_000_000_000L);
    }
}
