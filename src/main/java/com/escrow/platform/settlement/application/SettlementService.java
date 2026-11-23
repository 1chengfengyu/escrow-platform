package com.escrow.platform.settlement.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.escrow.platform.fund.application.EscrowService;
import com.escrow.platform.fund.application.FundAccountService;
import com.escrow.platform.fund.application.FundLedgerGuard;
import com.escrow.platform.fund.application.JournalService;
import com.escrow.platform.fund.domain.AccountSubject;
import com.escrow.platform.fund.domain.EscrowDetailStatus;
import com.escrow.platform.fund.domain.JournalBizType;
import com.escrow.platform.fund.domain.JournalDirection;
import com.escrow.platform.fund.domain.JournalEventType;
import com.escrow.platform.fund.domain.JournalTriggerType;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.domain.Settlement;
import com.escrow.platform.settlement.domain.SettlementFeeCalculator;
import com.escrow.platform.settlement.domain.SettlementStateMachine;
import com.escrow.platform.settlement.domain.SettlementStatus;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import com.escrow.platform.settlement.infrastructure.mapper.SettlementMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 结算生成与托管释放（FR-SETTLE.GEN.01、FEE.01~.03、ESCROW.RELEASE.01、PAY.INTENT.01）。
 *
 * <p><b>一条事务</b>：结算单生成 → 托管明细推进 → 余额搬运 → 凭证批次 → 打款单创建。
 * 任一环节失败整体回滚，不出现「钱走了但结算单没生成」。</p>
 *
 * <p><b>金额口径</b>：应结 S 取托管明细金额；费率取托管单快照（发布时固化）；
 * {@code F = floor(S × rate / 10000)}，{@code X = S − F}。X = 0 时走零实付，
 * 资金冻结 → 平台，不新建在途、不生成打款单（FR-SETTLE.FEE.03）。</p>
 *
 * <p><b>幂等</b>：{@code uk_settlement_slot} 保证一名额一张结算单；
 * 调用方必须已用签约状态做线性化，重复调用会撞唯一键并整体回滚。</p>
 *
 * @since V4
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementMapper settlementMapper;
    private final PayoutOrderMapper payoutOrderMapper;
    private final EscrowService escrowService;
    private final FundAccountService fundAccountService;
    private final JournalService journalService;
    private final FundLedgerGuard fundLedgerGuard;

    /**
     * 结算输入。
     *
     * @param slotId     名额 ID
     * @param employerId 雇主用户 ID
     * @param workerId   劳动者用户 ID
     * @param trigger    触发方（USER / SYSTEM，写凭证批次审计）
     */
    public record SettlementRequest(Long slotId, Long employerId, Long workerId,
                                    JournalTriggerType trigger) {
    }

    /**
     * 结算结果。
     *
     * @param settlementId   结算单 ID
     * @param settlementNo   结算单号（兼作 payment_intent_no）
     * @param payableAmount  应结金额 S（分）
     * @param feeAmount      服务费 F（分）
     * @param netAmount      实付 X（分）
     * @param payoutId       打款单 ID；零实付时为 null
     */
    public record SettlementOutcome(Long settlementId, String settlementNo,
                                    long payableAmount, long feeAmount, long netAmount,
                                    Long payoutId) {
    }

    /**
     * 验收通过后生成结算并释放托管资金。
     *
     * @param request 结算输入
     * @return 结算结果
     * @throws IllegalStateException 托管明细不存在 / 不在已托管 / 资金账实不一致
     */
    @Transactional
    public SettlementOutcome settleAccepted(SettlementRequest request) {
        EscrowService.SlotEscrowInfo escrow = escrowService.slotEscrowInfo(request.slotId());
        if (escrow == null) {
            throw new IllegalStateException("名额没有托管明细 slotId=" + request.slotId());
        }
        if (escrow.status() != EscrowDetailStatus.ESCROW_DETAIL_HELD) {
            throw new IllegalStateException("托管明细不在已托管状态 slotId=" + request.slotId()
                    + " status=" + escrow.status());
        }

        long payable = escrow.amount();
        long fee = SettlementFeeCalculator.feeOf(payable, escrow.feeRate());
        long net = payable - fee;

        Settlement settlement = createSettlement(request.slotId(), payable, fee, net, escrow.feeRate());
        confirm(settlement);
        log.info("结算单已生成 settlementNo={} slotId={} payable={} fee={} net={} trigger={}",
                settlement.getSettlementNo(), request.slotId(), payable, fee, net, request.trigger());

        if (net == 0) {
            settleZeroPay(settlement, request, escrow);
            fundLedgerGuard.assertBalanced("SETTLE_ZERO_PAY", settlement.getSettlementNo());
            return new SettlementOutcome(settlement.getId(), settlement.getSettlementNo(),
                    payable, fee, net, null);
        }

        releaseToTransit(settlement, request, escrow, payable);
        Long payoutId = createPayout(settlement, net);
        fundLedgerGuard.assertBalanced("ESCROW_RELEASE", settlement.getSettlementNo());
        return new SettlementOutcome(settlement.getId(), settlement.getSettlementNo(),
                payable, fee, net, payoutId);
    }

    private Settlement createSettlement(Long slotId, long payable, long fee, long net, int feeRate) {
        Settlement settlement = new Settlement();
        settlement.setSlotId(slotId);
        settlement.setSettlementNo("ST" + IdWorker.getId());
        settlement.setPayableAmount(payable);
        settlement.setFeeAmount(fee);
        settlement.setNetAmount(net);
        settlement.setFeeRate(feeRate);
        settlement.setStatus(SettlementStatus.SETTLEMENT_PENDING_GENERATE);
        settlementMapper.insert(settlement);
        return settlement;
    }

    private void confirm(Settlement settlement) {
        SettlementStatus confirmed = SettlementStateMachine.transit(
                SettlementStatus.SETTLEMENT_PENDING_GENERATE, SettlementStateMachine.Event.CONFIRM);
        int rows = settlementMapper.update(null, new LambdaUpdateWrapper<Settlement>()
                .eq(Settlement::getId, settlement.getId())
                .eq(Settlement::getStatus, SettlementStatus.SETTLEMENT_PENDING_GENERATE)
                .set(Settlement::getStatus, confirmed));
        if (rows != 1) {
            throw new IllegalStateException("结算单确认失败 settlementNo=" + settlement.getSettlementNo());
        }
        settlement.setStatus(confirmed);
    }

    /** 零实付：冻结 → 平台，结算单直接完结，不经过在途。 */
    private void settleZeroPay(Settlement settlement, SettlementRequest request,
                               EscrowService.SlotEscrowInfo escrow) {
        escrowService.markReleasedOnZeroPay(request.slotId());
        FundAccountService.Transfer transfer = fundAccountService.settleZeroPay(
                request.employerId(), escrow.amount());
        journalService.post(JournalBizType.SETTLE_ZERO_PAY, settlement.getSettlementNo(),
                JournalEventType.SETTLE_ZERO_PAY, request.trigger(), "零实付结算",
                List.of(
                        new JournalService.EntryDraft(transfer.debitAccountId(),
                                transfer.debitSubject(), JournalDirection.DEBIT, escrow.amount()),
                        new JournalService.EntryDraft(transfer.creditAccountId(),
                                transfer.creditSubject(), JournalDirection.CREDIT, escrow.amount())));
        transitSettlement(settlement, SettlementStatus.SETTLEMENT_CONFIRMED,
                SettlementStateMachine.Event.ZERO_PAY_COMPLETE);
        log.info("零实付结算完结 settlementNo={} slotId={} amount={}",
                settlement.getSettlementNo(), request.slotId(), escrow.amount());
    }

    /** 实付 &gt; 0：冻结 → 在途，等待打款。 */
    private void releaseToTransit(Settlement settlement, SettlementRequest request,
                                  EscrowService.SlotEscrowInfo escrow, long payable) {
        escrowService.markReleasing(request.slotId());
        FundAccountService.Transfer transfer = fundAccountService.releaseToTransit(
                request.employerId(), payable);
        journalService.post(JournalBizType.ESCROW_RELEASE, settlement.getSettlementNo(),
                JournalEventType.ESCROW_RELEASE, request.trigger(), "验收释放托管资金",
                List.of(
                        new JournalService.EntryDraft(transfer.debitAccountId(),
                                AccountSubject.ACC_FROZEN, JournalDirection.DEBIT, payable),
                        new JournalService.EntryDraft(transfer.creditAccountId(),
                                AccountSubject.ACC_TRANSIT, JournalDirection.CREDIT, payable)));
        transitSettlement(settlement, SettlementStatus.SETTLEMENT_CONFIRMED,
                SettlementStateMachine.Event.START_PAYING);
    }

    private void transitSettlement(Settlement settlement, SettlementStatus from,
                                   SettlementStateMachine.Event event) {
        SettlementStatus target = SettlementStateMachine.transit(from, event);
        int rows = settlementMapper.update(null, new LambdaUpdateWrapper<Settlement>()
                .eq(Settlement::getId, settlement.getId())
                .eq(Settlement::getStatus, from)
                .set(Settlement::getStatus, target));
        if (rows != 1) {
            throw new IllegalStateException("结算单跃迁失败 settlementNo="
                    + settlement.getSettlementNo() + " from=" + from);
        }
        settlement.setStatus(target);
    }

    private Long createPayout(Settlement settlement, long net) {
        PayoutOrder payout = new PayoutOrder();
        payout.setSettlementId(settlement.getId());
        payout.setRequestNo("PO" + IdWorker.getId());
        payout.setAttemptSeq(1);
        payout.setAmount(net);
        payout.setStatus(PayoutStatus.PAYOUT_PENDING_EXECUTE);
        payout.setStatusEnteredAt(LocalDateTime.now());
        payoutOrderMapper.insert(payout);
        log.info("打款单已创建 payoutId={} requestNo={} settlementNo={} amount={}",
                payout.getId(), payout.getRequestNo(), settlement.getSettlementNo(), net);
        return payout.getId();
    }
}
