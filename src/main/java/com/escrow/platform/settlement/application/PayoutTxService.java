package com.escrow.platform.settlement.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.notification.NotificationDispatcher;
import com.escrow.platform.engagement.application.ContractQueryService;
import com.escrow.platform.fund.application.EscrowService;
import com.escrow.platform.fund.application.FundAccountService;
import com.escrow.platform.fund.application.FundLedgerGuard;
import com.escrow.platform.fund.application.JournalService;
import com.escrow.platform.fund.domain.AccountSubject;
import com.escrow.platform.fund.domain.JournalBizType;
import com.escrow.platform.fund.domain.JournalDirection;
import com.escrow.platform.fund.domain.JournalEventType;
import com.escrow.platform.fund.domain.JournalTriggerType;
import com.escrow.platform.settlement.domain.ChannelResult;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStateMachine;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.domain.Settlement;
import com.escrow.platform.settlement.domain.SettlementStateMachine;
import com.escrow.platform.settlement.domain.SettlementStatus;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import com.escrow.platform.settlement.infrastructure.mapper.SettlementMapper;
import com.escrow.platform.task.application.TaskQueryService;
import com.escrow.platform.task.application.TaskSlotOccupancyService;
import com.escrow.platform.task.domain.TaskSlot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 打款状态落库（SRS A.4 的 T1 / T3；T2 在事务外的 {@link PayoutService}）。
 *
 * <p><b>先落库再出站</b>：{@link #claimForDispatch} 用条件更新把「待执行 → 执行中」，
 * 抢不到就说明别的线程 / 实例已出站，直接放弃。</p>
 *
 * <p><b>未知不等于失败</b>：超时与应答丢失一律进 {@link PayoutStatus#PAYOUT_PENDING_VERIFICATION}，
 * 绝不当失败重试（规则 §6）。明确失败才允许下次尝试。</p>
 *
 * <p><b>幂等</b>：每次尝试一个新 {@code request_no}；{@code uk_payout_success} 保证同一结算单
 * 至多一条成功；重复回调因状态已不在「执行中」而被忽略。</p>
 *
 * @since V4
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutTxService {

    private final PayoutOrderMapper payoutOrderMapper;
    private final SettlementMapper settlementMapper;
    private final TaskSlotOccupancyService taskSlotOccupancyService;
    private final EscrowService escrowService;
    private final FundAccountService fundAccountService;
    private final JournalService journalService;
    private final FundLedgerGuard fundLedgerGuard;
    private final ContractQueryService contractQueryService;
    private final TaskQueryService taskQueryService;
    private final PayoutRuntimeConfig runtimeConfig;
    private final NotificationDispatcher notificationDispatcher;

    /**
     * 出站所需的打款快照。
     *
     * @param payoutId    打款单 ID
     * @param settlementId 结算单 ID
     * @param requestNo  请求号
     * @param amount     请求金额（分）
     * @param attemptSeq 尝试序号
     */
    public record PayoutDispatch(Long payoutId, Long settlementId, String requestNo,
                                 long amount, int attemptSeq) {
    }

    /**
     * 抢占执行权：待执行 → 执行中。
     *
     * @param payoutId 打款单 ID
     * @return 快照；已被其他线程抢占时返回 {@code null}
     */
    @Transactional
    public PayoutDispatch claimForDispatch(Long payoutId) {
        PayoutOrder order = payoutOrderMapper.selectById(payoutId);
        if (order == null) {
            log.warn("打款单不存在，跳过出站 payoutId={}", payoutId);
            return null;
        }
        PayoutStatus executing = PayoutStateMachine.transit(
                PayoutStatus.PAYOUT_PENDING_EXECUTE, PayoutStateMachine.Event.DISPATCH);
        int rows = payoutOrderMapper.update(null, new LambdaUpdateWrapper<PayoutOrder>()
                .eq(PayoutOrder::getId, payoutId)
                .eq(PayoutOrder::getStatus, PayoutStatus.PAYOUT_PENDING_EXECUTE)
                .set(PayoutOrder::getStatus, executing)
                .set(PayoutOrder::getStatusEnteredAt, LocalDateTime.now()));
        if (rows != 1) {
            log.info("打款单已被抢占，跳过出站 payoutId={} status={}", payoutId, order.getStatus());
            return null;
        }
        return new PayoutDispatch(order.getId(), order.getSettlementId(), order.getRequestNo(),
                order.getAmount(), order.getAttemptSeq());
    }

    /**
     * T3 成功：打款单成功 → 结算完成 → 托管释放 → 在途资金结清。
     *
     * <p>重复回调（通道投递两次）第二次进入时状态已不在执行中，直接返回，
     * 不会再走一次分账——这就是 T3 的幂等点。</p>
     *
     * @param payoutId     打款单 ID
     * @param actualAmount 通道实际出款金额（分）
     */
    @Transactional
    public void handleSuccess(Long payoutId, long actualAmount) {
        completeSuccess(payoutId, actualAmount, PayoutStatus.PAYOUT_EXECUTING,
                PayoutStateMachine.Event.SUCCEED, false);
    }

    /**
     * 通道成功但金额与请求不一致：转人工，不按任何一方金额自动落账。
     *
     * <p>按请求金额落账会多发钱，按实际金额落账会少发钱；两者都必须由人确认，
     * 因此只记录实际金额并进入 {@link PayoutStatus#PAYOUT_MANUAL_REVIEW}。</p>
     *
     * @param payoutId     打款单 ID
     * @param actualAmount 通道实际出款金额（分）
     */
    @Transactional
    public void handleAmountMismatch(Long payoutId, long actualAmount) {
        PayoutOrder payout = requirePayout(payoutId);
        if (payout.getStatus() != PayoutStatus.PAYOUT_EXECUTING) {
            log.info("打款单已不在执行中，忽略金额不一致事件 payoutId={} status={}",
                    payoutId, payout.getStatus());
            return;
        }
        requireReasonableActual(payout, actualAmount);
        int rows = payoutOrderMapper.update(null, new LambdaUpdateWrapper<PayoutOrder>()
                .eq(PayoutOrder::getId, payoutId)
                .eq(PayoutOrder::getStatus, PayoutStatus.PAYOUT_EXECUTING)
                .set(PayoutOrder::getStatus,
                        PayoutStateMachine.transit(PayoutStatus.PAYOUT_EXECUTING,
                                PayoutStateMachine.Event.AMOUNT_MISMATCH))
                .set(PayoutOrder::getActualAmount, actualAmount)
                .set(PayoutOrder::getChannelResult, ChannelResult.SUCCESS)
                .set(PayoutOrder::getStatusEnteredAt, LocalDateTime.now()));
        if (rows != 1) {
            throw new IllegalStateException("打款单金额不一致跃迁失败 payoutId=" + payoutId);
        }
        log.error("通道实际金额与请求不一致，转人工 payoutId={} request={} actual={}",
                payoutId, payout.getAmount(), actualAmount);
    }

    private void completeSuccess(Long payoutId, long actualAmount, PayoutStatus expectedFrom,
                                 PayoutStateMachine.Event event, boolean allowAmountChange) {
        PayoutOrder payout = requirePayout(payoutId);
        if (payout.getStatus() != expectedFrom) {
            log.info("打款单不在 {}，忽略成功落库 payoutId={} status={}",
                    expectedFrom, payoutId, payout.getStatus());
            return;
        }
        if (allowAmountChange) {
            requireReasonableActual(payout, actualAmount);
        } else if (actualAmount != payout.getAmount()) {
            throw new IllegalStateException("通道实际出款与请求金额不一致 payoutId=" + payoutId
                    + " request=" + payout.getAmount() + " actual=" + actualAmount);
        }
        Settlement settlement = requireSettlement(payout.getSettlementId());
        if (settlement.getStatus() != SettlementStatus.SETTLEMENT_PAYING) {
            throw new IllegalStateException("结算单不在打款中 settlementNo="
                    + settlement.getSettlementNo() + " status=" + settlement.getStatus());
        }
        ContractQueryService.ContractBrief contract =
                contractQueryService.contractOfSlot(settlement.getSlotId());
        if (contract == null) {
            throw new IllegalStateException("结算单找不到签约 slotId=" + settlement.getSlotId());
        }
        TaskSlot slot = taskSlotOccupancyService.slotOf(settlement.getSlotId());
        if (slot == null) {
            throw new IllegalStateException("结算单找不到名额 slotId=" + settlement.getSlotId());
        }
        Long employerId = taskQueryService.employerIdOf(contract.taskId());

        int payoutRows = payoutOrderMapper.update(null, new LambdaUpdateWrapper<PayoutOrder>()
                .eq(PayoutOrder::getId, payoutId)
                .eq(PayoutOrder::getStatus, expectedFrom)
                .set(PayoutOrder::getStatus, PayoutStateMachine.transit(expectedFrom, event))
                .set(PayoutOrder::getActualAmount, actualAmount)
                .set(PayoutOrder::getChannelResult, ChannelResult.SUCCESS)
                .set(PayoutOrder::getStatusEnteredAt, LocalDateTime.now()));
        if (payoutRows != 1) {
            throw new IllegalStateException("打款单成功跃迁失败 payoutId=" + payoutId);
        }

        int settlementRows = settlementMapper.update(null, new LambdaUpdateWrapper<Settlement>()
                .eq(Settlement::getId, settlement.getId())
                .eq(Settlement::getStatus, SettlementStatus.SETTLEMENT_PAYING)
                .set(Settlement::getStatus,
                        SettlementStateMachine.transit(SettlementStatus.SETTLEMENT_PAYING,
                                SettlementStateMachine.Event.SUCCEED)));
        if (settlementRows != 1) {
            throw new IllegalStateException("结算单完成跃迁失败 settlementNo="
                    + settlement.getSettlementNo());
        }

        escrowService.markReleasedAfterPayout(settlement.getSlotId());

        long payable = settlement.getPayableAmount();
        long fee = settlement.getFeeAmount();
        long employerRefund = payable - actualAmount - fee;
        FundAccountService.PayoutSettlement funds = fundAccountService.settlePayout(
                contract.workerId(), employerId, payable, actualAmount, fee, employerRefund);

        List<JournalService.EntryDraft> entries = new ArrayList<>(1 + funds.credits().size());
        entries.add(new JournalService.EntryDraft(funds.transitAccountId(), AccountSubject.ACC_TRANSIT,
                JournalDirection.DEBIT, funds.transitAmount()));
        for (FundAccountService.SettlementLeg leg : funds.credits()) {
            entries.add(new JournalService.EntryDraft(leg.accountId(), leg.subject(),
                    JournalDirection.CREDIT, leg.amount()));
        }
        journalService.post(JournalBizType.PAYOUT_SETTLE, settlement.getSettlementNo(),
                JournalEventType.PAYOUT_SETTLE, JournalTriggerType.SYSTEM,
                "打款成功结算", entries);

        taskSlotOccupancyService.completeAfterPayout(contract.taskId(), settlement.getSlotId());
        fundLedgerGuard.assertBalanced("PAYOUT_SETTLE", settlement.getSettlementNo());
        log.info("打款成功 payoutId={} settlementNo={} actual={} fee={} refund={}",
                payoutId, settlement.getSettlementNo(), actualAmount, fee, employerRefund);
        notificationDispatcher.payoutSucceeded(contract.workerId(), settlement.getSettlementNo(),
                actualAmount);
    }

    /**
     * T3 明确失败：额度未用尽 → 失败 + 结算待重试；额度用尽 → 待人工。
     *
     * @param payoutId 打款单 ID
     */
    @Transactional
    public void handleFail(Long payoutId) {
        PayoutOrder payout = requirePayout(payoutId);
        if (payout.getStatus() != PayoutStatus.PAYOUT_EXECUTING) {
            log.info("打款单已不在执行中，忽略重复失败回调 payoutId={} status={}",
                    payoutId, payout.getStatus());
            return;
        }
        boolean retryable = payout.getAttemptSeq() < runtimeConfig.maxAttempts();
        PayoutStateMachine.Event event = retryable
                ? PayoutStateMachine.Event.FAIL : PayoutStateMachine.Event.FAIL_LIMIT;
        PayoutStatus target = PayoutStateMachine.transit(PayoutStatus.PAYOUT_EXECUTING, event);
        int rows = payoutOrderMapper.update(null, new LambdaUpdateWrapper<PayoutOrder>()
                .eq(PayoutOrder::getId, payoutId)
                .eq(PayoutOrder::getStatus, PayoutStatus.PAYOUT_EXECUTING)
                .set(PayoutOrder::getStatus, target)
                .set(PayoutOrder::getChannelResult, ChannelResult.FAIL)
                .set(PayoutOrder::getStatusEnteredAt, LocalDateTime.now()));
        if (rows != 1) {
            throw new IllegalStateException("打款单失败跃迁失败 payoutId=" + payoutId);
        }

        if (retryable) {
            markSettlementPendingRetry(payout.getSettlementId());
        }
        log.warn("打款明确失败 payoutId={} attempt={}/{} retryable={}",
                payoutId, payout.getAttemptSeq(), runtimeConfig.maxAttempts(), retryable);
    }

    /**
     * 查证确认成功：待查证 → 成功，按请求金额完成分账。
     *
     * @param payoutId 打款单 ID
     */
    @Transactional
    public void handleVerifiedSuccess(Long payoutId) {
        PayoutOrder payout = requirePayout(payoutId);
        completeSuccess(payoutId, payout.getAmount(), PayoutStatus.PAYOUT_PENDING_VERIFICATION,
                PayoutStateMachine.Event.VERIFY_SUCCESS, false);
    }

    /**
     * 查证判定金额不一致：待查证 → 待人工（记录通道实际金额）。
     *
     * @param payoutId     打款单 ID
     * @param actualAmount 通道实际金额（分）
     */
    @Transactional
    public void handleVerifiedAmountMismatch(Long payoutId, long actualAmount) {
        PayoutOrder payout = requirePayout(payoutId);
        if (payout.getStatus() != PayoutStatus.PAYOUT_PENDING_VERIFICATION) {
            log.info("打款单不在待查证，忽略金额不一致 payoutId={} status={}",
                    payoutId, payout.getStatus());
            return;
        }
        requireReasonableActual(payout, actualAmount);
        int rows = payoutOrderMapper.update(null, new LambdaUpdateWrapper<PayoutOrder>()
                .eq(PayoutOrder::getId, payoutId)
                .eq(PayoutOrder::getStatus, PayoutStatus.PAYOUT_PENDING_VERIFICATION)
                .set(PayoutOrder::getStatus,
                        PayoutStateMachine.transit(PayoutStatus.PAYOUT_PENDING_VERIFICATION,
                                PayoutStateMachine.Event.VERIFY_LIMIT))
                .set(PayoutOrder::getActualAmount, actualAmount)
                .set(PayoutOrder::getChannelResult, ChannelResult.SUCCESS)
                .set(PayoutOrder::getStatusEnteredAt, LocalDateTime.now()));
        if (rows != 1) {
            throw new IllegalStateException("查证金额不一致跃迁失败 payoutId=" + payoutId);
        }
        log.error("查证金额不一致，转人工 payoutId={} request={} actual={}",
                payoutId, payout.getAmount(), actualAmount);
    }

    /**
     * 查证确认失败 / 通道无记录：可重试则判失败并转待重试，额度用尽则转人工。
     *
     * <p>只有 {@code NOT_FOUND}（通道确认没有这笔）与 {@code FAIL} 才能走到这里；
     * 查证调用自身失败必须留在原状态，绝不能当成"没有这笔"。</p>
     *
     * @param payoutId 打款单 ID
     */
    @Transactional
    public void handleVerificationFailed(Long payoutId) {
        PayoutOrder payout = requirePayout(payoutId);
        if (payout.getStatus() != PayoutStatus.PAYOUT_PENDING_VERIFICATION) {
            log.info("打款单不在待查证，忽略查证失败 payoutId={} status={}",
                    payoutId, payout.getStatus());
            return;
        }
        boolean retryable = payout.getAttemptSeq() < runtimeConfig.maxAttempts();
        PayoutStateMachine.Event event = retryable
                ? PayoutStateMachine.Event.VERIFY_FAIL : PayoutStateMachine.Event.VERIFY_LIMIT;
        int rows = payoutOrderMapper.update(null, new LambdaUpdateWrapper<PayoutOrder>()
                .eq(PayoutOrder::getId, payoutId)
                .eq(PayoutOrder::getStatus, PayoutStatus.PAYOUT_PENDING_VERIFICATION)
                .set(PayoutOrder::getStatus,
                        PayoutStateMachine.transit(PayoutStatus.PAYOUT_PENDING_VERIFICATION, event))
                .set(PayoutOrder::getStatusEnteredAt, LocalDateTime.now()));
        if (rows != 1) {
            throw new IllegalStateException("查证失败跃迁失败 payoutId=" + payoutId);
        }
        if (retryable) {
            markSettlementPendingRetry(payout.getSettlementId());
        }
        log.warn("查证判定未出款 payoutId={} attempt={}/{} retryable={}",
                payoutId, payout.getAttemptSeq(), runtimeConfig.maxAttempts(), retryable);
    }

    /**
     * 人工确认实际金额后落成功（v5-c4）。
     *
     * <p>实际金额可以与请求金额不同：差额通过「雇主退回」这一腿结清，
     * 总账仍是「在途 = 劳动者 + 平台 + 雇主退回」。因此这里放宽金额一致性，
     * 但仍限制在 0~请求金额之间。</p>
     *
     * @param payoutId     打款单 ID
     * @param actualAmount 人工确认的实际金额（分）
     */
    @Transactional
    public void handleManualConfirm(Long payoutId, long actualAmount) {
        completeSuccess(payoutId, actualAmount, PayoutStatus.PAYOUT_MANUAL_REVIEW,
                PayoutStateMachine.Event.MANUAL_CONFIRM, true);
        log.warn("人工确认打款完成 payoutId={} actual={}", payoutId, actualAmount);
    }

    /**
     * 对账补记：待查证 → 成功，按通道实际金额落账（V6，FR-RECON.HANDLE.01）。
     *
     * <p>只用于 {@code LOST_RESPONSE}（通道已成功、本地未知）。补记复用打款成功链路，
     * 因此分账、托管释放、名额完结与通知与正常成功完全一致。</p>
     *
     * @param payoutId     打款单 ID
     * @param actualAmount 通道实际出款金额（分）
     * @throws ApiException 409 打款单不在待查证
     */
    @Transactional
    public void handleReconBook(Long payoutId, long actualAmount) {
        PayoutOrder payout = requirePayout(payoutId);
        if (payout.getStatus() != PayoutStatus.PAYOUT_PENDING_VERIFICATION) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "打款单不在待查证，不能补记 payoutId=" + payoutId + " status=" + payout.getStatus());
        }
        completeSuccess(payoutId, actualAmount, PayoutStatus.PAYOUT_PENDING_VERIFICATION,
                PayoutStateMachine.Event.VERIFY_SUCCESS, true);
        log.warn("对账补记完成 payoutId={} actual={}", payoutId, actualAmount);
    }

    /**
     * 对账冲正：本地成功但通道无记录，把分账原路冲回在途并回退到待重试（V6，FR-RECON.HANDLE.01）。
     *
     * <p>顺序：<b>先冲资金，再改状态</b>。任一腿余额不足抛 {@code INVALID_STATE_TRANSITION}，
     * 整个事务回滚，差异单保持「待处理」，绝不透支。冲正后打款单转失败、结算单转待重试、
     * 名额转待结算，由 V5 的重试链路重新出站。托管明细保持「已释放」不回退（V6 已知偏差）。</p>
     *
     * @param payoutId 打款单 ID
     * @throws ApiException 409 打款单不在成功态 / 结算单不在已完结 / 某腿余额不足
     */
    @Transactional
    public void handleReconReverse(Long payoutId) {
        PayoutOrder payout = requirePayout(payoutId);
        if (payout.getStatus() != PayoutStatus.PAYOUT_SUCCESS) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "打款单不在成功态，不能冲正 payoutId=" + payoutId + " status=" + payout.getStatus());
        }
        Settlement settlement = requireSettlement(payout.getSettlementId());
        if (settlement.getStatus() != SettlementStatus.SETTLEMENT_COMPLETED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "结算单不在已完结，不能冲正 settlementNo=" + settlement.getSettlementNo()
                            + " status=" + settlement.getStatus());
        }
        ContractQueryService.ContractBrief contract =
                contractQueryService.contractOfSlot(settlement.getSlotId());
        if (contract == null) {
            throw new IllegalStateException("结算单找不到签约 slotId=" + settlement.getSlotId());
        }
        Long employerId = taskQueryService.employerIdOf(contract.taskId());

        long actual = payout.getActualAmount() == null ? payout.getAmount() : payout.getActualAmount();
        long fee = settlement.getFeeAmount();
        long employerRefund = settlement.getPayableAmount() - actual - fee;
        FundAccountService.PayoutReverse funds = fundAccountService.reversePayout(
                contract.workerId(), employerId, actual, fee, employerRefund);

        List<JournalService.EntryDraft> entries = new ArrayList<>(1 + funds.debits().size());
        for (FundAccountService.ReverseLeg leg : funds.debits()) {
            entries.add(new JournalService.EntryDraft(leg.accountId(), leg.subject(),
                    JournalDirection.DEBIT, leg.amount()));
        }
        entries.add(new JournalService.EntryDraft(funds.transitAccountId(), AccountSubject.ACC_TRANSIT,
                JournalDirection.CREDIT, funds.transitAmount()));
        journalService.post(JournalBizType.RECON_ADJUST, settlement.getSettlementNo(),
                JournalEventType.RECON_REVERSE, JournalTriggerType.OPERATOR,
                "对账冲正", entries);

        int payoutRows = payoutOrderMapper.update(null, new LambdaUpdateWrapper<PayoutOrder>()
                .eq(PayoutOrder::getId, payoutId)
                .eq(PayoutOrder::getStatus, PayoutStatus.PAYOUT_SUCCESS)
                .set(PayoutOrder::getStatus,
                        PayoutStateMachine.transit(PayoutStatus.PAYOUT_SUCCESS,
                                PayoutStateMachine.Event.REVERSE))
                .set(PayoutOrder::getStatusEnteredAt, LocalDateTime.now()));
        if (payoutRows != 1) {
            throw new IllegalStateException("打款单冲正跃迁失败 payoutId=" + payoutId);
        }

        int settlementRows = settlementMapper.update(null, new LambdaUpdateWrapper<Settlement>()
                .eq(Settlement::getId, settlement.getId())
                .eq(Settlement::getStatus, SettlementStatus.SETTLEMENT_COMPLETED)
                .set(Settlement::getStatus,
                        SettlementStateMachine.transit(SettlementStatus.SETTLEMENT_COMPLETED,
                                SettlementStateMachine.Event.RECON_ROLLBACK))
                .set(Settlement::getUpdatedAt, LocalDateTime.now()));
        if (settlementRows != 1) {
            throw new IllegalStateException("结算单冲正跃迁失败 settlementNo="
                    + settlement.getSettlementNo());
        }

        taskSlotOccupancyService.reopenAfterRecon(contract.taskId(), settlement.getSlotId());
        fundLedgerGuard.assertBalanced("RECON_REVERSE", settlement.getSettlementNo());
        log.warn("对账冲正完成 payoutId={} settlementNo={} worker={} fee={} employerRefund={}",
                payoutId, settlement.getSettlementNo(), actual, fee, employerRefund);
    }

    /**
     * 对账差额调整：本地与通道金额不一致，把差额在劳动者与雇主之间搬运（V6，FR-RECON.HANDLE.01）。
     *
     * <p>{@code delta = 本地金额 − 通道金额}：多于 0 说明劳动者多记、雇主少退，
     * 借方为劳动者；少于 0 方向相反。打款单与结算单状态不变，只补一笔调整分录。</p>
     *
     * @param payoutId     打款单 ID
     * @param localAmount  本地记账金额（分）
     * @param channelAmount 通道实际金额（分）
     * @throws ApiException 409 打款单不在成功态 / 结算单不在已完结 / 某腿余额不足
     */
    @Transactional
    public void handleReconAdjust(Long payoutId, long localAmount, long channelAmount) {
        PayoutOrder payout = requirePayout(payoutId);
        if (payout.getStatus() != PayoutStatus.PAYOUT_SUCCESS) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "打款单不在成功态，不能差额调整 payoutId=" + payoutId + " status=" + payout.getStatus());
        }
        Settlement settlement = requireSettlement(payout.getSettlementId());
        if (settlement.getStatus() != SettlementStatus.SETTLEMENT_COMPLETED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "结算单不在已完结，不能差额调整 settlementNo=" + settlement.getSettlementNo()
                            + " status=" + settlement.getStatus());
        }
        long delta = localAmount - channelAmount;
        if (delta == 0) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER,
                    "本地与通道金额一致，无需差额调整 payoutId=" + payoutId);
        }
        ContractQueryService.ContractBrief contract =
                contractQueryService.contractOfSlot(settlement.getSlotId());
        if (contract == null) {
            throw new IllegalStateException("结算单找不到签约 slotId=" + settlement.getSlotId());
        }
        Long employerId = taskQueryService.employerIdOf(contract.taskId());

        FundAccountService.Transfer transfer =
                fundAccountService.adjustDifference(contract.workerId(), employerId, delta);
        long amount = Math.abs(delta);
        journalService.post(JournalBizType.RECON_ADJUST, settlement.getSettlementNo(),
                JournalEventType.RECON_DIFF_ADJUST, JournalTriggerType.OPERATOR,
                "对账差额调整", List.of(
                        new JournalService.EntryDraft(transfer.debitAccountId(),
                                transfer.debitSubject(), JournalDirection.DEBIT, amount),
                        new JournalService.EntryDraft(transfer.creditAccountId(),
                                transfer.creditSubject(), JournalDirection.CREDIT, amount)));
        fundLedgerGuard.assertBalanced("RECON_DIFF_ADJUST", settlement.getSettlementNo());
        log.warn("对账差额调整完成 payoutId={} settlementNo={} delta={}",
                payoutId, settlement.getSettlementNo(), delta);
    }

    private void markSettlementPendingRetry(Long settlementId) {
        int settlementRows = settlementMapper.update(null, new LambdaUpdateWrapper<Settlement>()
                .eq(Settlement::getId, settlementId)
                .eq(Settlement::getStatus, SettlementStatus.SETTLEMENT_PAYING)
                .set(Settlement::getStatus,
                        SettlementStateMachine.transit(SettlementStatus.SETTLEMENT_PAYING,
                                SettlementStateMachine.Event.FAIL_RETRY)));
        if (settlementRows != 1) {
            throw new IllegalStateException("结算单转待重试失败 settlementId=" + settlementId);
        }
    }

    /**
     * T3 未知：执行中 → 待查证，不动结算单、不重发。
     *
     * @param payoutId 打款单 ID
     * @param result   超时 / 应答丢失
     */
    @Transactional
    public void handleUnknown(Long payoutId, ChannelResult result) {
        PayoutOrder payout = requirePayout(payoutId);
        if (payout.getStatus() != PayoutStatus.PAYOUT_EXECUTING) {
            log.info("打款单已不在执行中，忽略未知回调 payoutId={} status={}",
                    payoutId, payout.getStatus());
            return;
        }
        int rows = payoutOrderMapper.update(null, new LambdaUpdateWrapper<PayoutOrder>()
                .eq(PayoutOrder::getId, payoutId)
                .eq(PayoutOrder::getStatus, PayoutStatus.PAYOUT_EXECUTING)
                .set(PayoutOrder::getStatus,
                        PayoutStateMachine.transit(PayoutStatus.PAYOUT_EXECUTING,
                                PayoutStateMachine.Event.UNKNOWN))
                .set(PayoutOrder::getChannelResult, result)
                .set(PayoutOrder::getStatusEnteredAt, LocalDateTime.now()));
        if (rows != 1) {
            throw new IllegalStateException("打款单未知态跃迁失败 payoutId=" + payoutId);
        }
        log.error("打款结果未知，进入待查证 payoutId={} result={} 不重发", payoutId, result);
    }

    /**
     * 调度补偿：执行中超时未回调 → 待查证（不重发）。
     *
     * @param payoutId 打款单 ID
     * @param cutoff   账龄截止时间
     * @return 是否成功推进
     */
    @Transactional
    public boolean markStuckUnknown(Long payoutId, LocalDateTime cutoff) {
        int rows = payoutOrderMapper.update(null, new LambdaUpdateWrapper<PayoutOrder>()
                .eq(PayoutOrder::getId, payoutId)
                .eq(PayoutOrder::getStatus, PayoutStatus.PAYOUT_EXECUTING)
                .le(PayoutOrder::getStatusEnteredAt, cutoff)
                .set(PayoutOrder::getStatus, PayoutStatus.PAYOUT_PENDING_VERIFICATION)
                .set(PayoutOrder::getStatusEnteredAt, LocalDateTime.now()));
        if (rows == 1) {
            log.error("打款执行中超时，转待查证 payoutId={} 不重发", payoutId);
        }
        return rows == 1;
    }

    private PayoutOrder requirePayout(Long payoutId) {
        PayoutOrder payout = payoutOrderMapper.selectById(payoutId);
        if (payout == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "打款单不存在");
        }
        return payout;
    }

    /** 人工确认 / 金额不一致时的实际金额必须落在 0~请求金额之间：超额出款需要平台另行补钱，不在本状态机内。 */
    private static void requireReasonableActual(PayoutOrder payout, long actualAmount) {
        if (actualAmount < 0 || actualAmount > payout.getAmount()) {
            throw new IllegalArgumentException("实际金额必须在 0~" + payout.getAmount()
                    + " 之间，实际=" + actualAmount);
        }
    }

    private Settlement requireSettlement(Long settlementId) {
        Settlement settlement = settlementMapper.selectById(settlementId);
        if (settlement == null) {
            throw new IllegalStateException("结算单不存在 settlementId=" + settlementId);
        }
        return settlement;
    }
}
