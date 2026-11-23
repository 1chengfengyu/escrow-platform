package com.escrow.platform;

import com.escrow.platform.common.chat.api.AdminChatController;
import com.escrow.platform.common.chat.api.ChatController;
import com.escrow.platform.common.chat.api.ConversationListItemView;
import com.escrow.platform.common.chat.api.ConversationView;
import com.escrow.platform.common.chat.api.CreateConversationRequest;
import com.escrow.platform.common.chat.api.MessagePageQuery;
import com.escrow.platform.common.chat.api.MessageView;
import com.escrow.platform.common.chat.api.SendMessageRequest;
import com.escrow.platform.common.chat.application.ChatConversationService;
import com.escrow.platform.common.chat.application.ChatEventDispatcher;
import com.escrow.platform.common.chat.application.ChatMessageService;
import com.escrow.platform.common.chat.application.ChatUnreadService;
import com.escrow.platform.common.chat.domain.ChatConversation;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 私聊功能真库验收（V11，CARD-13，补遗 §9 第 1~3、5~7、9 条 + 事件接线 + 取证选条）。
 *
 * <p>必须真库的原因：唯一键开会话、分页上限、未读游标、身份过滤都由 SQL 语义决定，
 * 内存替身测的是自己写的假实现（docs/verification.md 第 3 节）。</p>
 *
 * <p>隔离方式：账号手机号由 {@link #PHONE_SEQ} 递增生成、任务用雪花 ID，
 * {@code @AfterEach} 按本用例登记的行精确清理，不触碰其它数据。</p>
 *
 * @since V11
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V23ChatIntegrationTest {

    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    @Autowired
    private ChatConversationService conversationService;
    @Autowired
    private ChatMessageService messageService;
    @Autowired
    private ChatUnreadService unreadService;
    @Autowired
    private ChatEventDispatcher chatEventDispatcher;
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

    private String employerPhone;
    private String workerPhone;
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
        employerPhone = nextPhone();
        workerPhone = nextPhone();
        employerId = fixture.user(employerPhone, "艾雇主", UserRole.EMPLOYER, UserStatus.ACTIVE);
        workerId = fixture.user(workerPhone, "卫劳动者", UserRole.WORKER, UserStatus.ACTIVE);
        fixture.enableWorkerIdentity(workerId);
        taskId = fixture.recruitingTask(employerId, "私聊验收任务");
        employer = new LoginUser(employerId, employerPhone, UserRole.EMPLOYER, IdentityType.EMPLOYER);
        worker = new LoginUser(workerId, workerPhone, UserRole.WORKER, IdentityType.WORKER);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    /** 补遗 §9-1：未报名劳动者从任务详情发起 INQUIRY 并收到回复；重复发起返回既有会话。 */
    @Test
    void frMsgChat01Chat02InquiryBeforeApplicationShouldWork() {
        ConversationView created = conversationService.createInquiry(worker,
                new CreateConversationRequest(taskId, ChatScope.INQUIRY, null));
        assertThat(created.scope()).isEqualTo(ChatScope.INQUIRY);
        assertThat(created.status()).isEqualTo(ChatConversationStatus.OPEN);
        assertThat(created.taskTitle()).isEqualTo("私聊验收任务");
        assertThat(created.employerId()).isEqualTo(employerId);
        assertThat(created.workerId()).isEqualTo(workerId);

        ConversationView again = conversationService.createInquiry(worker,
                new CreateConversationRequest(taskId, ChatScope.INQUIRY, null));
        assertThat(again.id()).as("唯一键兜底：重复发起返回既有会话").isEqualTo(created.id());

        MessageView first = messageService.send(worker, created.id(),
                new SendMessageRequest("你好，这个任务还招人吗？", null));
        MessageView reply = messageService.send(employer, created.id(),
                new SendMessageRequest("还在招，明天能到岗吗？", null));

        PageView<MessageView> page = messageService.page(worker, created.id(),
                new MessagePageQuery(1, 20, null));
        assertThat(page.items()).extracting(MessageView::content)
                .containsExactly("你好，这个任务还招人吗？", "还在招，明天能到岗吗？");
        assertThat(page.items()).extracting(MessageView::id)
                .containsExactly(first.id(), reply.id());

        PageView<MessageView> delta = messageService.page(worker, created.id(),
                new MessagePageQuery(1, 20, reply.id()));
        assertThat(delta.items()).as("afterId 增量为空即没有新消息").isEmpty();

        assertThat(unreadService.unreadOf(worker, created.id()).unreadCount()).isEqualTo(1L);
        unreadService.markRead(worker, created.id());
        assertThat(unreadService.unreadOf(worker, created.id()).unreadCount()).isZero();
        unreadService.markRead(worker, created.id());
        assertThat(unreadService.unreadOf(worker, created.id()).unreadCount())
                .as("标记已读幂等").isZero();
    }

    /** 补遗 §9-2：雇主从劳动者资料发起沟通；会话视图只带公开昵称，不泄露手机号 / 口令。 */
    @Test
    void frMsgChat01EmployerInitiatedChatShouldNotLeakPrivateFields() {
        ConversationView view = conversationService.createInquiry(employer,
                new CreateConversationRequest(taskId, ChatScope.INQUIRY, workerId));

        assertThat(view.workerNickname()).isEqualTo("卫劳动者");
        assertThat(view.employerNickname()).isEqualTo("艾雇主");
        assertThat(view.toString()).doesNotContain(workerPhone).doesNotContain(employerPhone)
                .doesNotContain("$2A$10$");

        ConversationView workerSide = conversationService.createInquiry(worker,
                new CreateConversationRequest(taskId, ChatScope.INQUIRY, null));
        assertThat(workerSide.id()).as("同一任务同一对双方只有一个咨询会话").isEqualTo(view.id());
    }

    /** 补遗 §9-2 失败路径：目标账号未开通劳动者身份时返回 404，不暴露账号状态。 */
    @Test
    void frMsgChat01EmployerShouldRejectTargetWithoutWorkerIdentity() {
        long outsiderId = fixture.user(nextPhone(), "未开身份", UserRole.EMPLOYER, UserStatus.ACTIVE);

        assertThatThrownBy(() -> conversationService.createInquiry(employer,
                new CreateConversationRequest(taskId, ChatScope.INQUIRY, outsiderId)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 补遗 §9-3：报名 / 录用事件各自建会话，N 名额 N 会话互不串，重复事件幂等。 */
    @Test
    void frMsgChat01ApplyAndHireShouldCreateIsolatedConversations() {
        long secondWorkerId = fixture.user(nextPhone(), "第二劳动者", UserRole.WORKER, UserStatus.ACTIVE);
        fixture.enableWorkerIdentity(secondWorkerId);
        long applicationId = 9_000_001L;
        long contractA = 9_000_011L;
        long contractB = 9_000_012L;

        chatEventDispatcher.applicationCreated(taskId, applicationId, employerId, workerId);
        chatEventDispatcher.contractCreated(taskId, contractA, employerId, workerId);
        chatEventDispatcher.contractCreated(taskId, contractB, employerId, secondWorkerId);
        chatEventDispatcher.contractCreated(taskId, contractA, employerId, workerId);

        PageView<ConversationListItemView> list = conversationService.list(employer, null, 1, 50);
        assertThat(list.total()).as("报名 1 + 签约 2，重复事件不新建").isEqualTo(3L);

        ChatConversation application = conversationMapper.selectByUniqueKey(ChatScope.APPLICATION,
                taskId, applicationId, employerId, workerId);
        ChatConversation first = conversationMapper.selectByUniqueKey(ChatScope.CONTRACT, taskId,
                contractA, employerId, workerId);
        ChatConversation second = conversationMapper.selectByUniqueKey(ChatScope.CONTRACT, taskId,
                contractB, employerId, secondWorkerId);
        assertThat(application).isNotNull();
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();

        messageService.send(worker, first.getId(), new SendMessageRequest("我的交付标准是什么", null));
        PageView<MessageView> otherThread = messageService.page(employer, second.getId(),
                new MessagePageQuery(1, 20, null));
        assertThat(otherThread.items()).as("两个签约会话不串消息").isEmpty();
    }

    /** 补遗 §9-5：正序分页单页 ≤50；接口层没有编辑 / 撤回 / 删除路由。 */
    @Test
    void frMsgChat02MessagePagingShouldCapAt50AndStayAppendOnly() {
        long conversationId = fixture.conversation(taskId, ChatScope.INQUIRY, 0L, employerId, workerId,
                workerId, ChatConversationStatus.OPEN);
        LocalDateTime base = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        for (int i = 1; i <= 60; i++) {
            fixture.message(conversationId, i % 2 == 0 ? employerId : workerId, "第" + i + "条",
                    base.plusSeconds(i));
        }

        PageView<MessageView> page = messageService.page(worker, conversationId,
                new MessagePageQuery(1, 200, null));
        assertThat(page.items()).as("前端传 200 也夹到 50").hasSize(50);
        assertThat(page.items().get(0).content()).isEqualTo("第1条");
        assertThat(page.items().get(49).content()).isEqualTo("第50条");
        assertThat(page.total()).isEqualTo(60L);

        PageView<MessageView> secondPage = messageService.page(worker, conversationId,
                new MessagePageQuery(2, 50, null));
        assertThat(secondPage.items()).extracting(MessageView::content)
                .containsExactlyElementsOf(IntStream.rangeClosed(51, 60)
                        .mapToObj(i -> "第" + i + "条").toList());

        assertThat(Arrays.stream(ChatController.class.getDeclaredMethods())
                .noneMatch(m -> m.isAnnotationPresent(PutMapping.class)
                        || m.isAnnotationPresent(DeleteMapping.class)
                        || m.isAnnotationPresent(PatchMapping.class)))
                .as("用户端没有编辑 / 撤回 / 删除入口").isTrue();
        assertThat(Arrays.stream(AdminChatController.class.getDeclaredMethods())
                .noneMatch(m -> m.isAnnotationPresent(PutMapping.class)
                        || m.isAnnotationPresent(DeleteMapping.class)
                        || m.isAnnotationPresent(PatchMapping.class)))
                .as("管理端只读取证").isTrue();
    }

    /** 补遗 §9-6：疑似联系方式只标记不拦截，正文原样送达，审计不落命中原文。 */
    @Test
    void frMsgChat03ContactHintShouldMarkButStillDeliver() {
        long conversationId = fixture.conversation(taskId, ChatScope.INQUIRY, 0L, employerId, workerId,
                workerId, ChatConversationStatus.OPEN);
        String withContact = "加我手机 13800001111 详聊";

        MessageView sent = messageService.send(worker, conversationId,
                new SendMessageRequest(withContact, null));

        assertThat(sent.contactHint()).isTrue();
        assertThat(sent.content()).as("只标记，不拦截也不改写").isEqualTo(withContact);
        PageView<MessageView> page = messageService.page(employer, conversationId,
                new MessagePageQuery(1, 20, null));
        assertThat(page.items()).extracting(MessageView::content).containsExactly(withContact);

        assertThat(countOf("SELECT COUNT(*) FROM audit_log WHERE detail LIKE '%13800001111%'"))
                .as("审计不存命中原文").isZero();
    }

    /** 补遗 §9-7：同一账号双身份时，列表与未读按当前身份整体切换。 */
    @Test
    void frMsgChat01IdentitySwitchShouldFlipListAndUnread() {
        String bothPhone = nextPhone();
        long bothId = fixture.user(bothPhone, "双身份", UserRole.EMPLOYER, UserStatus.ACTIVE);
        fixture.enableWorkerIdentity(bothId);
        String otherEmployerPhone = nextPhone();
        long otherEmployerId = fixture.user(otherEmployerPhone, "另一雇主", UserRole.EMPLOYER,
                UserStatus.ACTIVE);
        long otherTaskId = fixture.recruitingTask(otherEmployerId, "另一雇主任务");

        chatEventDispatcher.contractCreated(taskId, 9_100_001L, bothId, workerId);
        chatEventDispatcher.contractCreated(otherTaskId, 9_100_002L, otherEmployerId, bothId);

        LoginUser asEmployer = new LoginUser(bothId, bothPhone, UserRole.EMPLOYER, IdentityType.EMPLOYER);
        LoginUser asWorker = new LoginUser(bothId, bothPhone, UserRole.WORKER, IdentityType.WORKER);

        PageView<ConversationListItemView> employerList = conversationService.list(asEmployer, null, 1, 50);
        PageView<ConversationListItemView> workerList = conversationService.list(asWorker, null, 1, 50);
        assertThat(employerList.total()).isEqualTo(1L);
        assertThat(workerList.total()).isEqualTo(1L);

        ChatConversation employerSide = conversationMapper.selectByUniqueKey(ChatScope.CONTRACT, taskId,
                9_100_001L, bothId, workerId);
        ChatConversation workerSide = conversationMapper.selectByUniqueKey(ChatScope.CONTRACT, otherTaskId,
                9_100_002L, otherEmployerId, bothId);
        assertThat(employerList.items().get(0).id())
                .as("雇主身份只看到自己做雇主的会话").isEqualTo(employerSide.getId());
        assertThat(workerList.items().get(0).id())
                .as("劳动者身份只看到自己做劳动者的会话").isEqualTo(workerSide.getId());

        LoginUser otherEmployer = new LoginUser(otherEmployerId, otherEmployerPhone,
                UserRole.EMPLOYER, IdentityType.EMPLOYER);
        messageService.send(otherEmployer, workerSide.getId(), new SendMessageRequest("明天开工", null));

        assertThat(unreadService.unreadCount(asWorker).unreadCount()).isEqualTo(1L);
        assertThat(unreadService.unreadCount(asEmployer).unreadCount()).isZero();
    }

    /** 补遗 §9-9：被禁用账号不能发新消息，历史消息仍可读。 */
    @Test
    void frAcctStatus01DisabledAccountShouldNotSendButCanReadHistory() {
        long conversationId = fixture.conversation(taskId, ChatScope.INQUIRY, 0L, employerId, workerId,
                workerId, ChatConversationStatus.OPEN);
        fixture.message(conversationId, employerId, "欢迎咨询", LocalDateTime.now());
        jdbcTemplate.update("UPDATE platform_user SET status = 'DISABLED' WHERE id = ?", workerId);

        assertThatThrownBy(() -> messageService.send(worker, conversationId,
                new SendMessageRequest("我还能发吗", null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        PageView<MessageView> page = messageService.page(worker, conversationId,
                new MessagePageQuery(1, 20, null));
        assertThat(page.items()).extracting(MessageView::content).containsExactly("欢迎咨询");
    }

    /** 补遗 §5.4：取证只接受该任务该对双方会话内的消息，别的劳动者的消息拒收。 */
    @Test
    void frMsgChat02EvidenceSelectionShouldRejectMessageOfAnotherPair() {
        long conversationId = fixture.conversation(taskId, ChatScope.CONTRACT, 9_200_001L,
                employerId, workerId, employerId, ChatConversationStatus.OPEN);
        long messageId = fixture.message(conversationId, workerId, "交付物已上传", LocalDateTime.now());

        List<MessageView> evidence = messageService.messagesOfTaskPair(taskId, employerId, workerId,
                List.of(messageId));
        assertThat(evidence).extracting(MessageView::content).containsExactly("交付物已上传");

        long otherWorkerId = fixture.user(nextPhone(), "别家劳动者", UserRole.WORKER, UserStatus.ACTIVE);
        assertThatThrownBy(() -> messageService.messagesOfTaskPair(taskId, employerId, otherWorkerId,
                List.of(messageId)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    private long countOf(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private static String nextPhone() {
        return "13" + String.format("%09d", PHONE_SEQ.incrementAndGet() % 1_000_000_000L);
    }
}
