package com.escrow.platform.engagement.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.common.chat.application.ChatEventDispatcher;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.dispute.application.DisputeService;
import com.escrow.platform.engagement.domain.AcceptanceDecision;
import com.escrow.platform.engagement.domain.AcceptanceTrigger;
import com.escrow.platform.engagement.domain.ContractStateMachine;
import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.engagement.domain.EngagementContract;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementContractMapper;
import com.escrow.platform.fund.application.EscrowService;
import com.escrow.platform.fund.domain.JournalTriggerType;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.settlement.application.SettlementService;
import com.escrow.platform.task.application.TaskQueryService;
import com.escrow.platform.task.application.TaskSlotOccupancyService;
import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.TaskSlot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验收判定的事务边界（FR-ACCEPT.MANUAL.01、GUARD.01、RESULT.01~.02）。
 *
 * <p>三写一致：签约「已交付 → 已完成」、名额「已交付 → 待结算 / 待裁决」、
 * 托管明细「已托管 → 释放中 / 冻结」在<b>同一本地事务</b>内推进；
 * 线性化点是签约行的条件更新（{@code WHERE id=? AND status='CONTRACT_DELIVERED'}），
 * 并发验收 / 拒收只有一个能抢到。</p>
 *
 * <p>守卫顺序固定：争议优先（{@code PENDING_ARBITRATION -> DISPUTE_ACTIVE}）→
 * 非已交付拒绝 → 归属校验。这与判定表 D-01 的优先级 R5 &gt; R2 &gt; R4 一致。</p>
 *
 * <p>拒收不改签约状态：合同仍为「已交付」，由 V5 争议裁决决定放款或退款。</p>
 *
 * @since V4
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcceptanceTxService {

    private static final String CONTRACT_NOT_FOUND_MESSAGE = "签约不存在";
    private static final String NOT_EMPLOYER_MESSAGE = "仅任务雇主可执行验收";
    private static final String NOT_OWNER_MESSAGE = "无权验收他人任务的签约";
    private static final String NOT_DELIVERED_MESSAGE = "当前签约不可验收（未交付或已处理）";

    private final EngagementContractMapper contractMapper;
    private final TaskQueryService taskQueryService;
    private final TaskSlotOccupancyService taskSlotOccupancyService;
    private final EscrowService escrowService;
    private final DisputeService disputeService;
    private final SettlementService settlementService;
    /** 私聊事件（CARD-11）：无剩余可推进名额时归档会话，失败不影响验收。 */
    private final ChatEventDispatcher chatEventDispatcher;

    /**
     * 验收结果。
     *
     * @param contractId     签约 ID
     * @param contractStatus 验收后的签约状态
     * @param slotStatus     验收后的名额状态
     * @param disputeId      拒收产生的争议单 ID；通过时为 null
     * @param settlementId   结算单 ID（v4-c2 填充）；拒收时为 null
     * @param payoutId       打款单 ID（v4-c2 填充）；零实付与拒收时为 null
     */
    public record AcceptanceOutcome(Long contractId, ContractStatus contractStatus, SlotStatus slotStatus,
                                    Long disputeId, Long settlementId, Long payoutId) {
    }

    /**
     * 雇主手工验收（通过 / 拒收）。
     *
     * @param current    会话主体，必须是该任务雇主
     * @param contractId 签约 ID
     * @param decision   验收结论
     * @param reason     拒收理由，通过时忽略
     * @return 验收结果
     * @throws ApiException 401 / 403 / 404 / 409
     */
    @Transactional
    public AcceptanceOutcome decide(LoginUser current, Long contractId,
                                    AcceptanceDecision decision, String reason) {
        requireEmployer(current);
        EngagementContract contract = requireContract(contractId);
        Long employerId = taskQueryService.employerIdOf(contract.getTaskId());
        if (!employerId.equals(current.userId())) {
            log.warn("越权验收 contractId={} taskId={} userId={}",
                    contractId, contract.getTaskId(), current.userId());
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_OWNER_MESSAGE);
        }
        return decide(contract, employerId, decision, reason, AcceptanceTrigger.USER);
    }

    /**
     * 48 小时自动验收（FR-ACCEPT.AUTO.01，trigger_source 记为 SYSTEM）。
     *
     * @param contractId 签约 ID
     * @return 验收结果；签约已不处于已交付时返回 {@code null}（被并发处理，跳过）
     */
    @Transactional
    public AcceptanceOutcome autoAccept(Long contractId) {
        EngagementContract contract = requireContract(contractId);
        TaskSlot slot = taskSlotOccupancyService.slotOf(contract.getSlotId());
        if (slot == null || slot.getStatus() != SlotStatus.SLOT_DELIVERED
                || contract.getStatus() != ContractStatus.CONTRACT_DELIVERED) {
            return null;
        }
        Long employerId = taskQueryService.employerIdOf(contract.getTaskId());
        return decide(contract, employerId, AcceptanceDecision.PASS, null, AcceptanceTrigger.SYSTEM);
    }

    private AcceptanceOutcome decide(EngagementContract contract, Long employerId,
                                     AcceptanceDecision decision, String reason,
                                     AcceptanceTrigger trigger) {
        TaskSlot slot = taskSlotOccupancyService.slotOf(contract.getSlotId());
        if (slot == null) {
            throw new IllegalStateException("签约找不到名额 contractId=" + contract.getId()
                    + " slotId=" + contract.getSlotId());
        }
        // R5：争议优先于一切验收操作（FR-ACCEPT.GUARD.01）
        if (slot.getStatus() == SlotStatus.SLOT_PENDING_ARBITRATION) {
            throw new ApiException(ErrorCode.DISPUTE_ACTIVE, "争议处理中，暂不可验收");
        }
        if (contract.getStatus() != ContractStatus.CONTRACT_DELIVERED
                || slot.getStatus() != SlotStatus.SLOT_DELIVERED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, NOT_DELIVERED_MESSAGE);
        }

        if (decision == AcceptanceDecision.REJECT) {
            return reject(contract, slot, reason, trigger);
        }
        return accept(contract, employerId, trigger);
    }

    private AcceptanceOutcome reject(EngagementContract contract, TaskSlot slot,
                                     String reason, AcceptanceTrigger trigger) {
        taskSlotOccupancyService.rejectToArbitration(contract.getTaskId(), contract.getSlotId());
        escrowService.freezeForDispute(contract.getSlotId());
        Long disputeId = disputeService.openEmployerReject(contract.getSlotId(), reason);
        log.info("验收拒收 contractId={} slotId={} disputeId={} trigger={}",
                contract.getId(), contract.getSlotId(), disputeId, trigger);
        return new AcceptanceOutcome(contract.getId(), contract.getStatus(),
                SlotStatus.SLOT_PENDING_ARBITRATION, disputeId, null, null);
    }

    private AcceptanceOutcome accept(EngagementContract contract, Long employerId,
                                     AcceptanceTrigger trigger) {
        ContractStatus completed = ContractStateMachine.transit(contract.getStatus(),
                ContractStateMachine.Event.ACCEPT);
        int claimed = contractMapper.update(null, new LambdaUpdateWrapper<EngagementContract>()
                .eq(EngagementContract::getId, contract.getId())
                .eq(EngagementContract::getStatus, ContractStatus.CONTRACT_DELIVERED)
                .set(EngagementContract::getStatus, completed));
        if (claimed != 1) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, NOT_DELIVERED_MESSAGE);
        }

        SettlementService.SettlementOutcome settlement = settlementService.settleAccepted(
                new SettlementService.SettlementRequest(contract.getSlotId(), employerId,
                        contract.getWorkerId(), journalTrigger(trigger)));
        if (settlement.netAmount() == 0) {
            taskSlotOccupancyService.completeOnZeroPay(contract.getTaskId(), contract.getSlotId());
        } else {
            taskSlotOccupancyService.acceptForSettlement(contract.getTaskId(), contract.getSlotId());
        }
        log.info("验收通过 contractId={} slotId={} trigger={} settlementNo={} net={} payoutId={}",
                contract.getId(), contract.getSlotId(), trigger, settlement.settlementNo(),
                settlement.netAmount(), settlement.payoutId());
        // 仅当该任务已无可推进名额（全部终态）时归档会话
        if (!taskSlotOccupancyService.hasActiveSlot(contract.getTaskId())) {
            chatEventDispatcher.archiveByTaskAfterCommit(contract.getTaskId());
        }
        return new AcceptanceOutcome(contract.getId(), completed,
                settlement.netAmount() == 0 ? SlotStatus.SLOT_COMPLETED : SlotStatus.SLOT_PENDING_SETTLEMENT,
                null, settlement.settlementId(), settlement.payoutId());
    }

    private static JournalTriggerType journalTrigger(AcceptanceTrigger trigger) {
        return trigger == AcceptanceTrigger.SYSTEM
                ? JournalTriggerType.SYSTEM : JournalTriggerType.USER;
    }

    private EngagementContract requireContract(Long contractId) {
        EngagementContract contract = contractMapper.selectById(contractId);
        if (contract == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, CONTRACT_NOT_FOUND_MESSAGE);
        }
        return contract;
    }

    private void requireEmployer(LoginUser current) {
        if (current == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "请先登录");
        }
        if (current.currentIdentity() != IdentityType.EMPLOYER) {
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_EMPLOYER_MESSAGE);
        }
    }
}
