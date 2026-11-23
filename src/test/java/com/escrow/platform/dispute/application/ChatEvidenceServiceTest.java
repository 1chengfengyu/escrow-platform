package com.escrow.platform.dispute.application;

import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.chat.api.MessageView;
import com.escrow.platform.common.chat.application.ChatMessageService;
import com.escrow.platform.common.chat.domain.ChatMessageType;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.dispute.api.ChatEvidenceRequest;
import com.escrow.platform.dispute.api.DisputeEvidenceView;
import com.escrow.platform.dispute.domain.Dispute;
import com.escrow.platform.dispute.domain.DisputeEvidence;
import com.escrow.platform.dispute.domain.DisputeInitiatorType;
import com.escrow.platform.dispute.domain.DisputeStatus;
import com.escrow.platform.dispute.infrastructure.mapper.DisputeEvidenceMapper;
import com.escrow.platform.engagement.application.ContractQueryService;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.application.TaskQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 争议会话取证测试（V11，CARD-12，补遗 §5.4）。
 *
 * @since V11
 */
@ExtendWith(MockitoExtension.class)
class ChatEvidenceServiceTest {

    private static final long DISPUTE_ID = 50L;
    private static final long SLOT_ID = 600L;
    private static final long CONTRACT_ID = 300L;
    private static final long TASK_ID = 10L;
    private static final long EMPLOYER_ID = 2001L;
    private static final long WORKER_ID = 1001L;
    private static final long CONVERSATION_ID = 9001L;

    private static final LoginUser WORKER =
            new LoginUser(WORKER_ID, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Mock
    private DisputeService disputeService;
    @Mock
    private ContractQueryService contractQueryService;
    @Mock
    private TaskQueryService taskQueryService;
    @Mock
    private ChatMessageService chatMessageService;
    @Mock
    private DisputeEvidenceMapper disputeEvidenceMapper;
    @Mock
    private AuditRecorder auditRecorder;

    @InjectMocks
    private ChatEvidenceService service;

    /** 成功：复制消息为证据，正文截断到 1000，审计只记会话与消息区间。 */
    @Test
    void copyShouldPersistEvidenceAndAuditWithoutContent() {
        Dispute dispute = dispute(DisputeStatus.DISPUTE_PENDING);
        when(disputeService.require(DISPUTE_ID)).thenReturn(dispute);
        when(disputeService.isParty(WORKER, dispute)).thenReturn(true);
        when(contractQueryService.contractOfSlot(SLOT_ID))
                .thenReturn(new ContractQueryService.ContractBrief(CONTRACT_ID, TASK_ID, SLOT_ID, WORKER_ID));
        when(taskQueryService.employerIdOf(TASK_ID)).thenReturn(EMPLOYER_ID);
        when(chatMessageService.messagesOfTaskPair(TASK_ID, EMPLOYER_ID, WORKER_ID, List.of(701L, 702L)))
                .thenReturn(List.of(
                        message(701L, "已按要求交付"),
                        message(702L, "长".repeat(1200))));
        assignEvidenceIds();

        List<DisputeEvidenceView> views = service.copyFromChat(WORKER, DISPUTE_ID,
                new ChatEvidenceRequest(List.of(701L, 702L)));

        assertThat(views).hasSize(2);
        assertThat(views.get(0).content()).isEqualTo("已按要求交付");
        assertThat(views.get(1).content()).hasSize(1000);
        assertThat(views).allSatisfy(view -> {
            assertThat(view.disputeId()).isEqualTo(DISPUTE_ID);
            assertThat(view.submitterId()).isEqualTo(WORKER_ID);
            assertThat(view.submitterType()).isEqualTo(DisputeInitiatorType.WORKER);
            assertThat(view.imageUrls()).isEmpty();
        });
        verify(disputeEvidenceMapper, times(2)).insert(any(DisputeEvidence.class));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditRecorder).record(eq("DISPUTE"), eq(String.valueOf(DISPUTE_ID)),
                eq("CHAT_EVIDENCE"), isNull(), isNull(), detail.capture());
        assertThat(detail.getValue())
                .contains("conversationIds=" + CONVERSATION_ID)
                .contains("messageIds=701-702")
                .doesNotContain("已按要求交付");
    }

    /** 非当事人：403，且不取证、不写审计。 */
    @Test
    void nonPartyShouldBeForbidden() {
        Dispute dispute = dispute(DisputeStatus.DISPUTE_PENDING);
        when(disputeService.require(DISPUTE_ID)).thenReturn(dispute);
        when(disputeService.isParty(WORKER, dispute)).thenReturn(false);

        assertThatThrownBy(() -> service.copyFromChat(WORKER, DISPUTE_ID,
                new ChatEvidenceRequest(List.of(701L))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(disputeEvidenceMapper, never()).insert(any(DisputeEvidence.class));
        verify(auditRecorder, never()).record(any(), any(), any(), any(), any(), any());
    }

    /** 争议已裁决：409，不能补证据。 */
    @Test
    void resolvedDisputeShouldBeInvalidState() {
        Dispute dispute = dispute(DisputeStatus.DISPUTE_RESOLVED);
        when(disputeService.require(DISPUTE_ID)).thenReturn(dispute);
        when(disputeService.isParty(WORKER, dispute)).thenReturn(true);

        assertThatThrownBy(() -> service.copyFromChat(WORKER, DISPUTE_ID,
                new ChatEvidenceRequest(List.of(701L))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);

        verify(chatMessageService, never()).messagesOfTaskPair(any(), any(), any(), any());
    }

    /** 争议不存在：404。 */
    @Test
    void missingDisputeShouldBe404() {
        when(disputeService.require(DISPUTE_ID))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "争议不存在"));

        assertThatThrownBy(() -> service.copyFromChat(WORKER, DISPUTE_ID,
                new ChatEvidenceRequest(List.of(701L))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 空 messageIds：400。 */
    @Test
    void emptyMessageIdsShouldBeInvalidParameter() {
        Dispute dispute = dispute(DisputeStatus.DISPUTE_PENDING);
        when(disputeService.require(DISPUTE_ID)).thenReturn(dispute);
        when(disputeService.isParty(WORKER, dispute)).thenReturn(true);

        assertThatThrownBy(() -> service.copyFromChat(WORKER, DISPUTE_ID,
                new ChatEvidenceRequest(List.of())))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    /** 超过 20 条：400，不查消息。 */
    @Test
    void tooManyMessagesShouldBeInvalidParameter() {
        Dispute dispute = dispute(DisputeStatus.DISPUTE_PENDING);
        when(disputeService.require(DISPUTE_ID)).thenReturn(dispute);
        when(disputeService.isParty(WORKER, dispute)).thenReturn(true);
        List<Long> ids = LongStream.rangeClosed(1, 21).boxed().toList();

        assertThatThrownBy(() -> service.copyFromChat(WORKER, DISPUTE_ID, new ChatEvidenceRequest(ids)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);

        verify(chatMessageService, never()).messagesOfTaskPair(any(), any(), any(), any());
    }

    /** 消息不属于该争议关联会话：400（由 chat 域校验后原样上抛）。 */
    @Test
    void foreignMessageShouldBeInvalidParameter() {
        Dispute dispute = dispute(DisputeStatus.DISPUTE_PENDING);
        when(disputeService.require(DISPUTE_ID)).thenReturn(dispute);
        when(disputeService.isParty(WORKER, dispute)).thenReturn(true);
        when(contractQueryService.contractOfSlot(SLOT_ID))
                .thenReturn(new ContractQueryService.ContractBrief(CONTRACT_ID, TASK_ID, SLOT_ID, WORKER_ID));
        when(taskQueryService.employerIdOf(TASK_ID)).thenReturn(EMPLOYER_ID);
        when(chatMessageService.messagesOfTaskPair(TASK_ID, EMPLOYER_ID, WORKER_ID, List.of(701L)))
                .thenThrow(new ApiException(ErrorCode.INVALID_PARAMETER, "消息不属于该争议关联的会话"));

        assertThatThrownBy(() -> service.copyFromChat(WORKER, DISPUTE_ID,
                new ChatEvidenceRequest(List.of(701L))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);

        verify(disputeEvidenceMapper, never()).insert(any(DisputeEvidence.class));
        verifyNoMoreInteractions(chatMessageService);
    }

    private void assignEvidenceIds() {
        List<Long> ids = new ArrayList<>(List.of(8801L, 8802L));
        doAnswer(invocation -> {
            DisputeEvidence evidence = invocation.getArgument(0);
            evidence.setId(ids.remove(0));
            return 1;
        }).when(disputeEvidenceMapper).insert(any(DisputeEvidence.class));
    }

    private static Dispute dispute(DisputeStatus status) {
        Dispute dispute = new Dispute();
        dispute.setId(DISPUTE_ID);
        dispute.setSlotId(SLOT_ID);
        dispute.setInitiatorType(DisputeInitiatorType.WORKER);
        dispute.setStatus(status);
        return dispute;
    }

    private static MessageView message(long id, String content) {
        return new MessageView(id, CONVERSATION_ID, EMPLOYER_ID, ChatMessageType.TEXT,
                content, false, LocalDateTime.now());
    }
}
