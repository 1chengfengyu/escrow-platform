package com.escrow.platform.engagement.application;

import com.escrow.platform.engagement.domain.AcceptanceDecision;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.settlement.application.PayoutService;
import com.escrow.platform.task.domain.SlotStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 验收编排（非事务）：事务内完成状态与资金推进，事务提交后再触发 T2 出站。
 *
 * <p>为什么拆成两个 Bean：T2 的通道调用必须在事务外（SRS A.4 T1/T2/T3），
 * 同类方法自调用不会经过 Spring 代理，{@code @Transactional} 会失效。</p>
 *
 * @since V4
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcceptanceService {

    private final AcceptanceTxService acceptanceTxService;
    private final PayoutService payoutService;

    /**
     * 雇主手工验收。
     *
     * @param current    会话主体
     * @param contractId 签约 ID
     * @param decision   验收结论
     * @param reason     拒收理由
     * @return 验收结果
     */
    public AcceptanceTxService.AcceptanceOutcome accept(LoginUser current, Long contractId,
                                                        AcceptanceDecision decision, String reason) {
        AcceptanceTxService.AcceptanceOutcome outcome =
                acceptanceTxService.decide(current, contractId, decision, reason);
        return completeAfterDispatch(outcome);
    }

    /**
     * 48 小时自动验收。
     *
     * @param contractId 签约 ID
     * @return 验收结果；已被并发处理时返回 {@code null}
     */
    public AcceptanceTxService.AcceptanceOutcome autoAccept(Long contractId) {
        AcceptanceTxService.AcceptanceOutcome outcome = acceptanceTxService.autoAccept(contractId);
        return completeAfterDispatch(outcome);
    }

    /**
     * 事务提交后触发 T2；同步通道确认成功时把返回给调用方的名额状态刷新为「已完结」。
     *
     * <p>事务内的结果是 {@code SLOT_PENDING_SETTLEMENT}（结算单已生成、打款尚未出站），
     * 而验收接口的语义是「验收的最终结果」。若打款在本次调用内确认成功，
     * 名额已经推进到 {@code SLOT_COMPLETED}，返回旧快照会让调用方误以为仍在待结算。</p>
     *
     * @param outcome 事务内的验收结果；可能为 {@code null}（自动验收被并发处理）
     * @return 与落库状态一致的验收结果
     */
    private AcceptanceTxService.AcceptanceOutcome completeAfterDispatch(
            AcceptanceTxService.AcceptanceOutcome outcome) {
        if (outcome == null || outcome.payoutId() == null) {
            return outcome;
        }
        boolean paid = payoutService.dispatch(outcome.payoutId());
        if (!paid || outcome.slotStatus() != SlotStatus.SLOT_PENDING_SETTLEMENT) {
            return outcome;
        }
        return new AcceptanceTxService.AcceptanceOutcome(
                outcome.contractId(), outcome.contractStatus(), SlotStatus.SLOT_COMPLETED,
                outcome.disputeId(), outcome.settlementId(), outcome.payoutId());
    }
}
