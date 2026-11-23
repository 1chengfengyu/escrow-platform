package com.escrow.platform.common.chat.application;

import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.chat.api.ConversationListItemView;
import com.escrow.platform.common.chat.api.ConversationView;
import com.escrow.platform.common.chat.api.CreateConversationRequest;
import com.escrow.platform.common.chat.domain.ChatConversation;
import com.escrow.platform.common.chat.domain.ChatConversationStatus;
import com.escrow.platform.common.chat.domain.ChatScope;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatConversationMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.application.AccountStatusQueryService;
import com.escrow.platform.identity.application.IdentityProfileService;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.application.TaskParticipationService;
import com.escrow.platform.task.application.TaskParticipationService.ParticipationFacts;
import com.escrow.platform.task.application.TaskQueryService;
import com.escrow.platform.task.application.TaskQueryService.TaskBrief;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 会话服务单元测试（V11，FR-MSG.CHAT.01，补遗 §2.1 / §2.2 / §5.1 / §5.2）。
 *
 * <p>只锁「守卫顺序、错误码、归属与唯一键回查的映射」，不证明并发：唯一键在并发下只产生一条
 * 会话必须由 CARD-13 的真库用例证明。</p>
 *
 * @since V11
 */
@ExtendWith(MockitoExtension.class)
class ChatConversationServiceTest {

    private static final long TASK_ID = 10L;
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
    private TaskParticipationService taskParticipationService;

    @Mock
    private TaskQueryService taskQueryService;

    @Mock
    private IdentityProfileService identityProfileService;

    @Mock
    private AccountStatusQueryService accountStatusQueryService;

    @Mock
    private AuditRecorder auditRecorder;

    @InjectMocks
    private ChatConversationService conversationService;

    // ==================== createInquiry ====================

    /** FR-MSG.CHAT.01：劳动者发起咨询，biz_id 固定 0，雇主取任务归属，发起人记本人。 */
    @Test
    void createInquiryShouldCreateInquiryWithZeroBizIdForWorker() {
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(recruitingTask());
        when(conversationMapper.insert(any(ChatConversation.class))).thenAnswer(invocation -> {
            invocation.<ChatConversation>getArgument(0).setId(CONVERSATION_ID);
            return 1;
        });
        when(taskQueryService.briefOf(TASK_ID)).thenReturn(brief());
        when(identityProfileService.nicknames(any())).thenReturn(
                Map.of(EMPLOYER_ID, "雇主甲", WORKER_ID, "劳动者乙"));

        ConversationView view = conversationService.createInquiry(WORKER,
                new CreateConversationRequest(TASK_ID, ChatScope.INQUIRY, null));

        assertThat(view.id()).isEqualTo(CONVERSATION_ID);
        assertThat(view.scope()).isEqualTo(ChatScope.INQUIRY);
        assertThat(view.taskTitle()).isEqualTo("周末小区派单");
        assertThat(view.employerNickname()).isEqualTo("雇主甲");
        assertThat(view.workerNickname()).isEqualTo("劳动者乙");

        ArgumentCaptor<ChatConversation> captor = ArgumentCaptor.forClass(ChatConversation.class);
        verify(conversationMapper).insert(captor.capture());
        ChatConversation saved = captor.getValue();
        assertThat(saved.getBizId()).isZero();
        assertThat(saved.getEmployerId()).isEqualTo(EMPLOYER_ID);
        assertThat(saved.getWorkerId()).isEqualTo(WORKER_ID);
        assertThat(saved.getInitiatorId()).isEqualTo(WORKER_ID);
        assertThat(saved.getStatus()).isEqualTo(ChatConversationStatus.OPEN);
        verifyNoInteractions(accountStatusQueryService);
    }

    /** FR-MSG.CHAT.01：重复发起撞唯一键 → 回查既有会话返回同一 ID，不报错。 */
    @Test
    void createInquiryShouldReturnExistingConversationOnDuplicateKey() {
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(recruitingTask());
        when(conversationMapper.insert(any(ChatConversation.class)))
                .thenThrow(new DuplicateKeyException("uk_chat_conv_scope_task_biz_pair"));
        ChatConversation existing = conversation(CONVERSATION_ID, ChatConversationStatus.OPEN);
        when(conversationMapper.selectByUniqueKey(any(), any(), any(), any(), any())).thenReturn(existing);
        when(taskQueryService.briefOf(TASK_ID)).thenReturn(brief());
        when(identityProfileService.nicknames(any())).thenReturn(Map.of());

        ConversationView view = conversationService.createInquiry(WORKER,
                new CreateConversationRequest(TASK_ID, ChatScope.INQUIRY, null));

        assertThat(view.id()).isEqualTo(CONVERSATION_ID);
    }

    /** 自己对自己：劳动者身份但本人就是任务发布者 → 403。 */
    @Test
    void createInquiryShouldRejectSelfConversation() {
        LoginUser selfEmployer = new LoginUser(EMPLOYER_ID, "self@example.com",
                UserRole.WORKER, IdentityType.WORKER);
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(recruitingTask());

        ApiException ex = catchThrowableOfType(() -> conversationService.createInquiry(selfEmployer,
                new CreateConversationRequest(TASK_ID, ChatScope.INQUIRY, null)), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(conversationMapper, never()).insert(any(ChatConversation.class));
    }

    /** 草稿对非雇主不可见：404，不暴露存在性。 */
    @Test
    void createInquiryShouldRejectDraftAsNotFound() {
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(
                new ParticipationFacts(TASK_ID, EMPLOYER_ID, null, null,
                        LocalDateTime.now().plusDays(1), 1));

        ApiException ex = catchThrowableOfType(() -> conversationService.createInquiry(WORKER,
                new CreateConversationRequest(TASK_ID, ChatScope.INQUIRY, null)), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(conversationMapper, never()).insert(any(ChatConversation.class));
    }

    /** 已撤单：非招募中 → 403（补遗口径）。 */
    @Test
    void createInquiryShouldRejectCancelledTask() {
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(
                new ParticipationFacts(TASK_ID, EMPLOYER_ID, LocalDateTime.now().minusDays(2),
                        LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1), 1));

        ApiException ex = catchThrowableOfType(() -> conversationService.createInquiry(WORKER,
                new CreateConversationRequest(TASK_ID, ChatScope.INQUIRY, null)), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    /** 已过结束时间：非招募中 → 403。 */
    @Test
    void createInquiryShouldRejectEndedTask() {
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(
                new ParticipationFacts(TASK_ID, EMPLOYER_ID, LocalDateTime.now().minusDays(2),
                        null, LocalDateTime.now().minusMinutes(1), 1));

        ApiException ex = catchThrowableOfType(() -> conversationService.createInquiry(WORKER,
                new CreateConversationRequest(TASK_ID, ChatScope.INQUIRY, null)), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    /** 雇主发起：目标账号未开通劳动者身份 → 404。 */
    @Test
    void createInquiryShouldRejectInactiveTargetWorker() {
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(recruitingTask());
        when(accountStatusQueryService.isActiveWorker(WORKER_ID)).thenReturn(false);

        ApiException ex = catchThrowableOfType(() -> conversationService.createInquiry(EMPLOYER,
                new CreateConversationRequest(TASK_ID, ChatScope.INQUIRY, WORKER_ID)), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(conversationMapper, never()).insert(any(ChatConversation.class));
    }

    /** 雇主发起：目标有效时雇主取当前账号，劳动者取目标账号。 */
    @Test
    void createInquiryShouldCreateFromEmployerToWorker() {
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(recruitingTask());
        when(accountStatusQueryService.isActiveWorker(WORKER_ID)).thenReturn(true);
        when(conversationMapper.insert(any(ChatConversation.class))).thenAnswer(invocation -> {
            invocation.<ChatConversation>getArgument(0).setId(CONVERSATION_ID);
            return 1;
        });
        when(taskQueryService.briefOf(TASK_ID)).thenReturn(brief());
        when(identityProfileService.nicknames(any())).thenReturn(Map.of());

        conversationService.createInquiry(EMPLOYER,
                new CreateConversationRequest(TASK_ID, ChatScope.INQUIRY, WORKER_ID));

        ArgumentCaptor<ChatConversation> captor = ArgumentCaptor.forClass(ChatConversation.class);
        verify(conversationMapper).insert(captor.capture());
        assertThat(captor.getValue().getEmployerId()).isEqualTo(EMPLOYER_ID);
        assertThat(captor.getValue().getWorkerId()).isEqualTo(WORKER_ID);
        assertThat(captor.getValue().getInitiatorId()).isEqualTo(EMPLOYER_ID);
    }

    /** 雇主发起未带 targetUserId → 400。 */
    @Test
    void createInquiryShouldRequireTargetForEmployer() {
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(recruitingTask());

        ApiException ex = catchThrowableOfType(() -> conversationService.createInquiry(EMPLOYER,
                new CreateConversationRequest(TASK_ID, ChatScope.INQUIRY, null)), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    /** 用户只能主动发起 INQUIRY；APPLICATION / CONTRACT 只由事件创建 → 400。 */
    @Test
    void createInquiryShouldRejectNonInquiryScope() {
        ApiException ex = catchThrowableOfType(() -> conversationService.createInquiry(WORKER,
                new CreateConversationRequest(TASK_ID, ChatScope.CONTRACT, null)), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(taskParticipationService);
    }

    /** 任务不存在（task 域抛 404）原样透出。 */
    @Test
    void createInquiryShouldPropagateTaskNotFound() {
        when(taskParticipationService.factsOf(TASK_ID))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在"));

        ApiException ex = catchThrowableOfType(() -> conversationService.createInquiry(WORKER,
                new CreateConversationRequest(TASK_ID, ChatScope.INQUIRY, null)), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ==================== ensure* ====================

    /** 报名事件建 APPLICATION 会话：biz_id = 报名 ID，发起人为系统 0。 */
    @Test
    void ensureApplicationShouldInsertApplicationConversation() {
        when(conversationMapper.insert(any(ChatConversation.class))).thenAnswer(invocation -> {
            invocation.<ChatConversation>getArgument(0).setId(CONVERSATION_ID);
            return 1;
        });

        Long id = conversationService.ensureApplication(TASK_ID, 55L, EMPLOYER_ID, WORKER_ID);

        assertThat(id).isEqualTo(CONVERSATION_ID);
        ArgumentCaptor<ChatConversation> captor = ArgumentCaptor.forClass(ChatConversation.class);
        verify(conversationMapper).insert(captor.capture());
        assertThat(captor.getValue().getScope()).isEqualTo(ChatScope.APPLICATION);
        assertThat(captor.getValue().getBizId()).isEqualTo(55L);
        assertThat(captor.getValue().getInitiatorId()).isZero();
    }

    /** 签约事件建 CONTRACT 会话：1 任务 N 签约靠 biz_id 互不串。 */
    @Test
    void ensureContractShouldUseContractIdAsBizId() {
        when(conversationMapper.insert(any(ChatConversation.class))).thenAnswer(invocation -> {
            invocation.<ChatConversation>getArgument(0).setId(CONVERSATION_ID);
            return 1;
        });

        conversationService.ensureContract(TASK_ID, 66L, EMPLOYER_ID, WORKER_ID);

        ArgumentCaptor<ChatConversation> captor = ArgumentCaptor.forClass(ChatConversation.class);
        verify(conversationMapper).insert(captor.capture());
        assertThat(captor.getValue().getScope()).isEqualTo(ChatScope.CONTRACT);
        assertThat(captor.getValue().getBizId()).isEqualTo(66L);
    }

    /** 归属参数不完整 → 400。 */
    @Test
    void ensureApplicationShouldRejectIncompleteOwnership() {
        ApiException ex = catchThrowableOfType(
                () -> conversationService.ensureApplication(TASK_ID, null, EMPLOYER_ID, WORKER_ID),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
        verify(conversationMapper, never()).insert(any(ChatConversation.class));
    }

    /** 事件重复触发：撞唯一键回查既有，返回同一 ID。 */
    @Test
    void ensureContractShouldBeIdempotentOnDuplicateKey() {
        when(conversationMapper.insert(any(ChatConversation.class)))
                .thenThrow(new DuplicateKeyException("uk_chat_conv_scope_task_biz_pair"));
        when(conversationMapper.selectByUniqueKey(any(), any(), any(), any(), any()))
                .thenReturn(conversation(CONVERSATION_ID, ChatConversationStatus.OPEN));

        assertThat(conversationService.ensureContract(TASK_ID, 66L, EMPLOYER_ID, WORKER_ID))
                .isEqualTo(CONVERSATION_ID);
    }

    // ==================== list ====================

    /** 管理员身份不得使用用户端会话列表 → 403。 */
    @Test
    void listShouldRejectAdminIdentity() {
        ApiException ex = catchThrowableOfType(
                () -> conversationService.list(ADMIN, null, 1, 20), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(conversationMapper);
    }

    /** 劳动者身份只查 worker_id，size 超 50 被夹紧，page=0 归一为 1。 */
    @Test
    void listShouldFilterByWorkerIdentityAndClampPaging() {
        when(conversationMapper.selectPageByIdentity(eq(WORKER_ID), eq(false), any(), eq(0), eq(50)))
                .thenReturn(List.of(conversation(CONVERSATION_ID, ChatConversationStatus.OPEN)));
        when(conversationMapper.countByIdentity(WORKER_ID, false, null)).thenReturn(1L);

        PageView<ConversationListItemView> view = conversationService.list(WORKER, null, 0, 99);

        assertThat(view.page()).isEqualTo(1);
        assertThat(view.size()).isEqualTo(50);
        assertThat(view.total()).isEqualTo(1L);
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).unreadCount()).isZero();
        verify(conversationMapper).selectPageByIdentity(WORKER_ID, false, null, 0, 50);
    }

    /** 雇主身份按 employer_id 查询，scope 原样透传。 */
    @Test
    void listShouldFilterByEmployerIdentityAndScope() {
        when(conversationMapper.selectPageByIdentity(eq(EMPLOYER_ID), eq(true), eq(ChatScope.CONTRACT),
                anyInt(), anyInt())).thenReturn(List.of());
        when(conversationMapper.countByIdentity(EMPLOYER_ID, true, ChatScope.CONTRACT)).thenReturn(0L);

        PageView<ConversationListItemView> view =
                conversationService.list(EMPLOYER, ChatScope.CONTRACT, 2, 10);

        assertThat(view.items()).isEmpty();
        assertThat(view.page()).isEqualTo(2);
        verify(conversationMapper).selectPageByIdentity(EMPLOYER_ID, true, ChatScope.CONTRACT, 10, 10);
    }

    /** 未登录 → 401。 */
    @Test
    void listShouldRejectAnonymous() {
        ApiException ex = catchThrowableOfType(
                () -> conversationService.list(null, null, 1, 20), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    // ==================== detail ====================

    /** 会话不存在 → 404。 */
    @Test
    void detailShouldThrow404WhenConversationMissing() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(null);

        ApiException ex = catchThrowableOfType(
                () -> conversationService.detail(WORKER, CONVERSATION_ID), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 非当事人 → 403 且写 CHAT 审计（补遗 §5.1）。 */
    @Test
    void detailShouldRejectNonPartyAndWriteAudit() {
        when(conversationMapper.selectById(CONVERSATION_ID))
                .thenReturn(conversation(CONVERSATION_ID, ChatConversationStatus.OPEN));
        LoginUser outsider = new LoginUser(4004L, "outsider@example.com",
                UserRole.WORKER, IdentityType.WORKER);

        ApiException ex = catchThrowableOfType(
                () -> conversationService.detail(outsider, CONVERSATION_ID), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(auditRecorder).record("CHAT", String.valueOf(CONVERSATION_ID), "FORBIDDEN_VIEW",
                null, null, "userId=" + outsider.userId());
        verifyNoInteractions(taskQueryService);
    }

    /** 当事人可见：带任务标题与双方公开昵称。 */
    @Test
    void detailShouldReturnTaskTitleAndNicknamesForParty() {
        ChatConversation conversation = conversation(CONVERSATION_ID, ChatConversationStatus.OPEN);
        conversation.setLastMessageAt(LocalDateTime.of(2026, 11, 21, 10, 0));
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation);
        when(taskQueryService.briefOf(TASK_ID)).thenReturn(brief());
        when(identityProfileService.nicknames(any())).thenReturn(
                Map.of(EMPLOYER_ID, "雇主甲", WORKER_ID, "劳动者乙"));

        ConversationView view = conversationService.detail(EMPLOYER, CONVERSATION_ID);

        assertThat(view.taskId()).isEqualTo(TASK_ID);
        assertThat(view.taskTitle()).isEqualTo("周末小区派单");
        assertThat(view.employerNickname()).isEqualTo("雇主甲");
        assertThat(view.workerNickname()).isEqualTo("劳动者乙");
        assertThat(view.lastMessageAt()).isEqualTo(LocalDateTime.of(2026, 11, 21, 10, 0));
        assertThat(view.status()).isEqualTo(ChatConversationStatus.OPEN);
        verifyNoInteractions(auditRecorder);
    }

    // ==================== archiveByTask ====================

    /** taskId 为空 → 400。 */
    @Test
    void archiveByTaskShouldRejectNullTaskId() {
        ApiException ex = catchThrowableOfType(
                () -> conversationService.archiveByTask(null), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(conversationMapper);
    }

    /** 归档只改 OPEN 行，重复触发返回 0 行不报错。 */
    @Test
    void archiveByTaskShouldBeIdempotent() {
        when(conversationMapper.archiveByTaskId(TASK_ID)).thenReturn(2);

        assertThat(conversationService.archiveByTask(TASK_ID)).isEqualTo(2);
        assertThat(conversationService.archiveByTask(TASK_ID)).isEqualTo(2);
        verify(conversationMapper, org.mockito.Mockito.times(2)).archiveByTaskId(TASK_ID);
    }

    // ==================== 辅助 ====================

    private static ParticipationFacts recruitingTask() {
        return new ParticipationFacts(TASK_ID, EMPLOYER_ID, LocalDateTime.now().minusDays(1),
                null, LocalDateTime.now().plusDays(1), 1);
    }

    private static TaskBrief brief() {
        return new TaskBrief(TASK_ID, "周末小区派单", EMPLOYER_ID,
                LocalDateTime.now().minusDays(1));
    }

    private static ChatConversation conversation(Long id, ChatConversationStatus status) {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(id);
        conversation.setScope(ChatScope.INQUIRY);
        conversation.setTaskId(TASK_ID);
        conversation.setBizId(0L);
        conversation.setEmployerId(EMPLOYER_ID);
        conversation.setWorkerId(WORKER_ID);
        conversation.setInitiatorId(WORKER_ID);
        conversation.setStatus(status);
        return conversation;
    }
}
