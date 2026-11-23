package com.escrow.platform.dispute.application;

import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.chat.application.ChatEventDispatcher;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.dispute.domain.Dispute;
import com.escrow.platform.dispute.domain.DisputeInitiatorType;
import com.escrow.platform.dispute.domain.DisputeResolution;
import com.escrow.platform.dispute.domain.DisputeStatus;
import com.escrow.platform.engagement.application.ContractQueryService;
import com.escrow.platform.engagement.application.ContractService;
import com.escrow.platform.fund.application.EscrowService;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.settlement.application.SettlementService;
import com.escrow.platform.task.application.TaskQueryService;
import com.escrow.platform.task.application.TaskSlotOccupancyService;
import com.escrow.platform.fund.domain.JournalTriggerType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 争议事务边界（V6）：申诉开争议、撤回、裁决放款 / 退款。
 *
 * <p><b>裁决放款的钱怎么走</b>：托管明细「冻结 → 已托管」，再复用验收结算
 * （已托管 → 释放中 → 在途），因此分账、服务费、打款单与正常验收完全一致；
 * 裁决退款则走「冻结 → 已退款」并把钱退回雇主。</p>
 *
 * <p>本类只做 T1（落库与状态），T2 出站在
 * {@link DisputeResolutionService} 里于事务提交后触发（SRS A.4）。</p>
 *
 * @since V6
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeTxService {

    private static final String NOT_PARTY_MESSAGE = "无权操作该争议单";
    private static final String CONTRACT_NOT_FOUND_MESSAGE = "签约不存在";

    private final DisputeService disputeService;
    private final ContractQueryService contractQueryService;
    private final ContractService contractService;
    private final TaskQueryService taskQueryService;
    private final TaskSlotOccupancyService taskSlotOccupancyService;
    private final EscrowService escrowService;
    private final SettlementService settlementService;
    private final AuditRecorder auditRecorder;
    /** 私聊事件（CARD-11）：退款使名额终态时归档会话，失败不影响裁决。 */
    private final ChatEventDispatcher chatEventDispatcher;

    /**
     * 裁决结果。
     *
     * @param disputeId 争议单 ID
     * @param payoutId  裁决放款产生的打款单 ID；退款时为 {@code null}
     */
    public record ResolutionOutcome(Long disputeId, Long payoutId) {
    }

    /**
     * 劳动者申诉开争议（FR-DISPUTE.OPEN.01）：冻结托管、名额转待裁决。
     *
     * @param current    会话主体，必须是该签约的劳动者
     * @param contractId 签约 ID
     * @param reason     申诉理由
     * @return 争议单 ID
     */
    @Transactional
    public Long openWorkerAppeal(LoginUser current, Long contractId, String reason) {
        ContractQueryService.ContractBrief contract = contractQueryService.contractOf(contractId);
        if (contract == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, CONTRACT_NOT_FOUND_MESSAGE);
        }
        if (!contract.workerId().equals(current.userId())) {
            log.warn("越权申诉 contractId={} userId={}", contractId, current.userId());
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_PARTY_MESSAGE);
        }
        if (disputeService.hasOpenDispute(contract.slotId())) {
            throw new ApiException(ErrorCode.DISPUTE_ACTIVE, "该名额已有未决争议");
        }
        try {
            taskSlotOccupancyService.openArbitration(contract.taskId(), contract.slotId());
        } catch (IllegalStateException ex) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "只有已交付的名额可以发起申诉");
        }
        escrowService.freezeForDispute(contract.slotId());
        Long disputeId = disputeService.openOrReuse(contract.slotId(),
                DisputeInitiatorType.WORKER, reason);
        auditRecorder.record("DISPUTE", String.valueOf(disputeId), "OPEN", null,
                DisputeStatus.DISPUTE_PENDING.name(),
                "slotId=" + contract.slotId() + " initiator=WORKER");
        log.info("劳动者申诉开争议 contractId={} slotId={} disputeId={}",
                contractId, contract.slotId(), disputeId);
        return disputeId;
    }

    /**
     * 撤回争议（FR-DISPUTE.WITHDRAW.01）：仅发起方，累计不超过 2 次。
     *
     * <p>撤回后名额回「已交付」、托管明细回「已托管」、验收倒计时重新起算。</p>
     *
     * @param current   会话主体，必须是争议发起方
     * @param disputeId 争议单 ID
     */
    @Transactional
    public void withdraw(LoginUser current, Long disputeId) {
        Dispute dispute = disputeService.require(disputeId);
        DisputeInitiatorType expected = initiatorOf(current);
        if (dispute.getInitiatorType() != expected) {
            log.warn("越权撤回争议 disputeId={} userId={}", disputeId, current.userId());
            throw new ApiException(ErrorCode.FORBIDDEN, "只有争议发起方可以撤回");
        }
        if (!disputeService.isParty(current, dispute)) {
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_PARTY_MESSAGE);
        }
        disputeService.withdraw(dispute);

        ContractQueryService.ContractBrief contract =
                contractQueryService.contractOfSlot(dispute.getSlotId());
        if (contract == null) {
            throw new IllegalStateException("争议找不到对应签约 slotId=" + dispute.getSlotId());
        }
        taskSlotOccupancyService.withdrawArbitration(contract.taskId(), contract.slotId());
        escrowService.unfreezeAfterDispute(contract.slotId());
        contractService.restartAcceptanceCountdown(contract.contractId());
        auditRecorder.record("DISPUTE", String.valueOf(disputeId), "WITHDRAW",
                DisputeStatus.DISPUTE_PENDING.name(), DisputeStatus.DISPUTE_WITHDRAWN.name(),
                "slotId=" + contract.slotId() + " initiator=" + dispute.getInitiatorType());
        log.info("争议撤回完成 disputeId={} slotId={} contractId={}",
                disputeId, contract.slotId(), contract.contractId());
    }

    /**
     * 管理员裁决（FR-DISPUTE.RESOLVE.01）。
     *
     * @param current    会话主体，必须是管理员
     * @param disputeId  争议单 ID
     * @param resolution RELEASE 放款 / REFUND 退款
     * @param basis      裁决依据
     * @return 裁决结果（放款时带打款单 ID）
     */
    @Transactional
    public ResolutionOutcome resolve(LoginUser current, Long disputeId,
                                     DisputeResolution resolution, String basis) {
        if (!DisputeService.isAdmin(current)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅管理员可裁决争议");
        }
        if (basis == null || basis.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "裁决依据不能为空");
        }
        Dispute dispute = disputeService.require(disputeId);
        if (dispute.getStatus() != DisputeStatus.DISPUTE_PENDING) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "争议不在待裁决，无法裁决");
        }
        ContractQueryService.ContractBrief contract =
                contractQueryService.contractOfSlot(dispute.getSlotId());
        if (contract == null) {
            throw new IllegalStateException("争议找不到对应签约 slotId=" + dispute.getSlotId());
        }
        Long employerId = taskQueryService.employerIdOf(contract.taskId());

        disputeService.markResolved(disputeId, resolution, basis.trim(), current.userId());
        auditRecorder.record("DISPUTE", String.valueOf(disputeId), resolution.name(),
                DisputeStatus.DISPUTE_PENDING.name(), DisputeStatus.DISPUTE_RESOLVED.name(),
                "basis=" + basis.trim());
        if (resolution == DisputeResolution.RELEASE) {
            return release(current, dispute, contract, employerId);
        }
        return refund(dispute, contract, employerId);
    }

    private ResolutionOutcome release(LoginUser current, Dispute dispute,
                                      ContractQueryService.ContractBrief contract, Long employerId) {
        escrowService.unfreezeAfterDispute(dispute.getSlotId());
        contractService.completeAfterArbitration(contract.contractId());
        SettlementService.SettlementOutcome settlement = settlementService.settleAccepted(
                new SettlementService.SettlementRequest(dispute.getSlotId(), employerId,
                        contract.workerId(), JournalTriggerType.OPERATOR));
        if (settlement.netAmount() == 0) {
            taskSlotOccupancyService.completeArbitration(contract.taskId(), dispute.getSlotId());
        } else {
            taskSlotOccupancyService.releaseArbitration(contract.taskId(), dispute.getSlotId());
        }
        log.warn("争议裁决放款 disputeId={} slotId={} settlementNo={} net={} operator={}",
                dispute.getId(), dispute.getSlotId(), settlement.settlementNo(),
                settlement.netAmount(), current.userId());
        return new ResolutionOutcome(dispute.getId(), settlement.payoutId());
    }

    private ResolutionOutcome refund(Dispute dispute,
                                     ContractQueryService.ContractBrief contract, Long employerId) {
        long amount = escrowService.refundFrozenSlot(dispute.getSlotId(), dispute.getId(), employerId);
        taskSlotOccupancyService.refundArbitration(contract.taskId(), dispute.getSlotId());
        contractService.cancelAfterArbitration(contract.contractId());
        // 退款后仅当该任务已无可推进名额（全部终态）时归档会话
        if (!taskSlotOccupancyService.hasActiveSlot(contract.taskId())) {
            chatEventDispatcher.archiveByTaskAfterCommit(contract.taskId());
        }
        log.warn("争议裁决退款 disputeId={} slotId={} employerId={} amount={}",
                dispute.getId(), dispute.getSlotId(), employerId, amount);
        return new ResolutionOutcome(dispute.getId(), null);
    }

    private static DisputeInitiatorType initiatorOf(LoginUser current) {
        if (current == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "请先登录");
        }
        return switch (current.currentIdentity()) {
            case EMPLOYER -> DisputeInitiatorType.EMPLOYER;
            case WORKER -> DisputeInitiatorType.WORKER;
            default -> throw new ApiException(ErrorCode.FORBIDDEN, "管理员不能撤回争议");
        };
    }
}
