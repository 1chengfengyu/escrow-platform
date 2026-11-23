package com.escrow.platform.dispute.application;

import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.chat.api.MessageView;
import com.escrow.platform.common.chat.application.ChatMessageService;
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
import com.escrow.platform.task.application.TaskQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 争议会话取证（V11，CARD-12，补遗 §5.4）。
 *
 * <p>把聊天消息<b>复制</b>成 {@code dispute_evidence}：聊天表只读不写，
 * 消息后续仍按原规则保留 / 归档，仲裁看到的是取证时刻的快照。</p>
 *
 * <p><b>不写资金审计流水</b>：只写 {@code DISPUTE/CHAT_EVIDENCE} 业务审计，
 * 记录会话 ID 与消息 ID 区间，<b>不落正文</b>（NFR-SEC.04）。</p>
 *
 * <p>校验顺序：争议存在 404 → 当事人 403 → 待裁决 409 → 参数 400 → 消息归属 400。
 * 顺序保证越权者拿不到「争议是否存在」以外的信息。</p>
 *
 * @since V11
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatEvidenceService {

    /** 与 {@link ChatMessageService} 一致，单次最多复制 20 条。 */
    private static final int MAX_MESSAGES = 20;
    /** {@code dispute_evidence.content} 容量上限。 */
    private static final int MAX_CONTENT_LENGTH = 1000;

    private static final String NOT_PARTY_MESSAGE = "无权为该争议提交证据";

    private final DisputeService disputeService;
    private final ContractQueryService contractQueryService;
    private final TaskQueryService taskQueryService;
    private final ChatMessageService chatMessageService;
    private final DisputeEvidenceMapper disputeEvidenceMapper;
    private final AuditRecorder auditRecorder;

    /**
     * 把指定消息复制为争议证据。
     *
     * @param current   当前登录主体，必须是争议当事人
     * @param disputeId 争议单 ID
     * @param request   消息 ID 列表，1~20 条
     * @return 新证据（按消息 ID 升序）
     * @throws ApiException 404 争议不存在 / 403 非当事人 / 409 非待裁决 / 400 参数或消息归属不合法
     */
    @Transactional
    public List<DisputeEvidenceView> copyFromChat(LoginUser current, Long disputeId,
                                                  ChatEvidenceRequest request) {
        Dispute dispute = disputeService.require(disputeId);
        if (!disputeService.isParty(current, dispute)) {
            log.warn("越权从会话取证 disputeId={} userId={}", disputeId,
                    current == null ? null : current.userId());
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_PARTY_MESSAGE);
        }
        if (dispute.getStatus() != DisputeStatus.DISPUTE_PENDING) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "争议不在待裁决，无法提交证据");
        }
        List<Long> messageIds = request == null ? null : request.messageIds();
        if (messageIds == null || messageIds.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "messageIds 不能为空");
        }
        if (messageIds.size() > MAX_MESSAGES) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "messageIds 最多 " + MAX_MESSAGES + " 条");
        }

        ContractQueryService.ContractBrief contract =
                contractQueryService.contractOfSlot(dispute.getSlotId());
        if (contract == null) {
            throw new IllegalStateException("争议找不到对应签约 disputeId=" + disputeId);
        }
        Long employerId = taskQueryService.employerIdOf(contract.taskId());
        if (employerId == null) {
            throw new IllegalStateException("签约找不到对应任务雇主 disputeId=" + disputeId);
        }

        List<MessageView> messages = chatMessageService.messagesOfTaskPair(
                contract.taskId(), employerId, contract.workerId(), messageIds);
        DisputeInitiatorType submitterType = current.currentIdentity() == IdentityType.EMPLOYER
                ? DisputeInitiatorType.EMPLOYER : DisputeInitiatorType.WORKER;
        LocalDateTime now = LocalDateTime.now();

        List<DisputeEvidenceView> views = new ArrayList<>(messages.size());
        for (MessageView message : messages) {
            DisputeEvidence evidence = new DisputeEvidence();
            evidence.setDisputeId(disputeId);
            evidence.setSubmitterId(current.userId());
            evidence.setSubmitterType(submitterType);
            evidence.setContent(truncate(message.content()));
            evidence.setCreatedAt(now);
            disputeEvidenceMapper.insert(evidence);
            views.add(new DisputeEvidenceView(evidence.getId(), disputeId, current.userId(),
                    submitterType, evidence.getContent(), List.of(), now));
        }

        auditRecorder.record("DISPUTE", String.valueOf(disputeId), "CHAT_EVIDENCE",
                null, null, auditDetail(messages));
        log.info("争议会话取证 disputeId={} submitterId={} count={} messageIds={}",
                disputeId, current.userId(), messages.size(), messageIds);
        return views;
    }

    /** 审计只记位置信息，不记正文。 */
    private static String auditDetail(List<MessageView> messages) {
        String conversationIds = messages.stream()
                .map(MessageView::conversationId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .map(String::valueOf)
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
        String range = messages.isEmpty()
                ? "none"
                : messages.get(0).id() + "-" + messages.get(messages.size() - 1).id();
        return "conversationIds=" + conversationIds + " messageIds=" + range;
    }

    private static String truncate(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= MAX_CONTENT_LENGTH ? content : content.substring(0, MAX_CONTENT_LENGTH);
    }
}
