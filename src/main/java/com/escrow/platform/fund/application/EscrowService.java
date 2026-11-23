package com.escrow.platform.fund.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.fund.domain.AccountSubject;
import com.escrow.platform.fund.domain.EscrowDetailStateMachine;
import com.escrow.platform.fund.domain.EscrowDetailStatus;
import com.escrow.platform.fund.domain.EscrowOrderStateMachine;
import com.escrow.platform.fund.domain.EscrowOrderStatus;
import com.escrow.platform.fund.domain.FundEscrowDetail;
import com.escrow.platform.fund.domain.FundEscrowOrder;
import com.escrow.platform.fund.domain.JournalBizType;
import com.escrow.platform.fund.domain.JournalDirection;
import com.escrow.platform.fund.domain.JournalEventType;
import com.escrow.platform.fund.domain.JournalTriggerType;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowDetailMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 托管单与托管明细（FR-ESCROW.HOLD.01~.03、FR-ESCROW.REFUND.01）。
 *
 * <p>发布时一次冻结整单：创建托管单（费率快照）→ 按名额生成 N 条明细（已托管）
 * → 冻结雇主可用余额 → 写一个 {@code ESCROW_HOLD} 批次。明细金额 = 单价，
 * 总额 = 单价 × 名额数，无尾差（SRS 3.4.2「托管金额拆分规则」）。</p>
 *
 * <p>退款以明细为单位，<b>先条件更新抢占明细</b>（{@code WHERE id=? AND status='ESCROW_DETAIL_HELD'}），
 * 受影响行数为 1 才计入退款金额；抢不到说明已被撤单 / 到期路径处理过，直接跳过。</p>
 *
 * <p>本类是 fund 域对 task 域暴露的唯一入口；task 域不得直调 fund 的 Mapper，
 * 也不得 join {@code fund_*} 表（project_rules §3 域间规则）。</p>
 *
 * @since V2
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EscrowService {

    private final FundEscrowOrderMapper fundEscrowOrderMapper;
    private final FundEscrowDetailMapper fundEscrowDetailMapper;
    private final FundAccountService fundAccountService;
    private final JournalService journalService;
    private final FundLedgerGuard fundLedgerGuard;

    /**
     * 一个名额的冻结草稿。
     *
     * @param slotId 名额 ID
     * @param amount 该名额冻结金额（分），V2 恒等于任务单价
     */
    public record SlotHold(Long slotId, long amount) {
    }

    /**
     * 退款结果。
     *
     * @param amount          本次实际退回金额（分）
     * @param refundedSlotIds 本次成功抢占并退款的明细对应名额 ID
     */
    public record RefundResult(long amount, List<Long> refundedSlotIds) {
    }

    /**
     * 验收结算所需的托管快照。
     *
     * @param detailId    托管明细 ID
     * @param escrowOrderId 托管单 ID
     * @param amount      该名额冻结额（分），即应结金额 S
     * @param feeRate     托管单上的费率快照（万分数）
     * @param status      明细当前状态
     */
    public record SlotEscrowInfo(Long detailId, Long escrowOrderId, long amount,
                                 int feeRate, EscrowDetailStatus status) {
    }

    /**
     * 发布托管：建单 + 生成明细 + 冻结余额 + 记账。
     *
     * @param taskId     任务 ID（一任务一单，靠 {@code uk_fund_escrow_order_task} 兜底）
     * @param employerId 雇主用户 ID
     * @param feeRate    服务费率快照（万分数）
     * @param holds      名额冻结草稿
     * @param bizNo      记账幂等键第二段（任务 ID）
     * @return 新建的托管单
     */
    @Transactional
    public FundEscrowOrder hold(Long taskId, Long employerId, int feeRate,
                                List<SlotHold> holds, String bizNo) {
        long totalAmount = holds.stream().mapToLong(SlotHold::amount).sum();

        FundEscrowOrder order = new FundEscrowOrder();
        order.setTaskId(taskId);
        order.setTotalAmount(totalAmount);
        order.setFeeRate(feeRate);
        order.setStatus(EscrowOrderStatus.ESCROW_ORDER_HELD);
        fundEscrowOrderMapper.insert(order);

        for (SlotHold hold : holds) {
            FundEscrowDetail detail = new FundEscrowDetail();
            detail.setEscrowOrderId(order.getId());
            detail.setSlotId(hold.slotId());
            detail.setAmount(hold.amount());
            detail.setStatus(EscrowDetailStatus.ESCROW_DETAIL_HELD);
            fundEscrowDetailMapper.insert(detail);
        }

        FundAccountService.Transfer transfer = fundAccountService.freeze(employerId, totalAmount);
        journalService.post(
                JournalBizType.ESCROW_HOLD,
                bizNo,
                JournalEventType.ESCROW_HOLD,
                JournalTriggerType.USER,
                "任务发布托管冻结",
                List.of(
                        new JournalService.EntryDraft(transfer.debitAccountId(), transfer.debitSubject(),
                                JournalDirection.DEBIT, totalAmount),
                        new JournalService.EntryDraft(transfer.creditAccountId(), transfer.creditSubject(),
                                JournalDirection.CREDIT, totalAmount)));
        fundLedgerGuard.assertBalanced("ESCROW_HOLD", bizNo);
        return order;
    }

    /**
     * 按名额退款：抢占明细 → 退回可用余额 → 记账 → 明细全终态则关闭托管单。
     *
     * <p>可重复调用：已退款明细条件更新 0 行，不会重复退款；
     * 本次一笔也没抢到时既不搬钱也不记账。</p>
     *
     * @param taskId     任务 ID
     * @param bizNo      记账幂等键第二段（如 {@code taskId:CANCEL}）
     * @param employerId 雇主用户 ID
     * @param slotIds    待退款名额 ID
     * @param trigger    触发方
     * @return 实际退款金额与成功抢占的名额
     */
    @Transactional
    public RefundResult refund(Long taskId, String bizNo, Long employerId,
                               List<Long> slotIds, JournalTriggerType trigger) {
        FundEscrowOrder order = fundEscrowOrderMapper.selectOne(new LambdaQueryWrapper<FundEscrowOrder>()
                .eq(FundEscrowOrder::getTaskId, taskId));
        if (order == null || slotIds.isEmpty()) {
            return new RefundResult(0L, List.of());
        }

        List<FundEscrowDetail> details = fundEscrowDetailMapper.selectList(
                new LambdaQueryWrapper<FundEscrowDetail>()
                        .eq(FundEscrowDetail::getEscrowOrderId, order.getId())
                        .in(FundEscrowDetail::getSlotId, slotIds));

        long refundedAmount = 0L;
        List<Long> refundedSlotIds = new ArrayList<>();
        for (FundEscrowDetail detail : details) {
            int rows = fundEscrowDetailMapper.update(null, new LambdaUpdateWrapper<FundEscrowDetail>()
                    .eq(FundEscrowDetail::getId, detail.getId())
                    .eq(FundEscrowDetail::getStatus, EscrowDetailStatus.ESCROW_DETAIL_HELD)
                    .set(FundEscrowDetail::getStatus,
                            EscrowDetailStateMachine.transit(EscrowDetailStatus.ESCROW_DETAIL_HELD,
                                    EscrowDetailStateMachine.Event.REFUND)));
            if (rows == 1) {
                refundedAmount += detail.getAmount();
                refundedSlotIds.add(detail.getSlotId());
            }
        }

        if (refundedAmount > 0) {
            FundAccountService.Transfer transfer = fundAccountService.refund(employerId, refundedAmount);
            journalService.post(
                    JournalBizType.ESCROW_REFUND,
                    bizNo,
                    JournalEventType.ESCROW_REFUND,
                    trigger,
                    "任务取消 / 到期退款",
                    List.of(
                            new JournalService.EntryDraft(transfer.debitAccountId(), transfer.debitSubject(),
                                    JournalDirection.DEBIT, refundedAmount),
                            new JournalService.EntryDraft(transfer.creditAccountId(), transfer.creditSubject(),
                                    JournalDirection.CREDIT, refundedAmount)));
            fundLedgerGuard.assertBalanced("ESCROW_REFUND", bizNo);
        }

        closeOrderIfAllDetailsTerminal(order.getId());
        return new RefundResult(refundedAmount, List.copyOf(refundedSlotIds));
    }

    /**
     * 查询某名额的托管明细与托管单费率快照（验收结算入口，只读）。
     *
     * @param slotId 名额 ID
     * @return 托管快照；明细不存在返回 {@code null}
     * @throws IllegalStateException 明细存在但托管单缺失（数据不一致）
     */
    public SlotEscrowInfo slotEscrowInfo(Long slotId) {
        FundEscrowDetail detail = fundEscrowDetailMapper.selectOne(
                new LambdaQueryWrapper<FundEscrowDetail>().eq(FundEscrowDetail::getSlotId, slotId));
        if (detail == null) {
            return null;
        }
        FundEscrowOrder order = fundEscrowOrderMapper.selectById(detail.getEscrowOrderId());
        if (order == null) {
            throw new IllegalStateException("托管明细找不到托管单 slotId=" + slotId
                    + " escrowOrderId=" + detail.getEscrowOrderId());
        }
        return new SlotEscrowInfo(detail.getId(), order.getId(), detail.getAmount(),
                order.getFeeRate(), detail.getStatus());
    }

    /**
     * 验收通过且实付 &gt; 0：明细「已托管 → 释放中」（FR-ESCROW.RELEASE.01）。
     *
     * @param slotId 名额 ID
     */
    @Transactional
    public void markReleasing(Long slotId) {
        transitEscrowDetail(slotId, EscrowDetailStatus.ESCROW_DETAIL_HELD,
                EscrowDetailStateMachine.Event.ACCEPT, "托管明细进入释放中");
    }

    /**
     * 验收通过且实付 = 0：明细「已托管 → 已释放」，资金直接进平台（FR-SETTLE.FEE.03）。
     *
     * @param slotId 名额 ID
     */
    @Transactional
    public void markReleasedOnZeroPay(Long slotId) {
        transitEscrowDetail(slotId, EscrowDetailStatus.ESCROW_DETAIL_HELD,
                EscrowDetailStateMachine.Event.ZERO_PAY, "托管明细零实付释放");
    }

    /**
     * 打款成功：明细「释放中 → 已释放」（FR-PAY.CONFIRM.01）。
     *
     * @param slotId 名额 ID
     */
    @Transactional
    public void markReleasedAfterPayout(Long slotId) {
        transitEscrowDetail(slotId, EscrowDetailStatus.ESCROW_DETAIL_RELEASING,
                EscrowDetailStateMachine.Event.SETTLE_SUCCESS, "托管明细打款成功释放");
    }

    /**
     * 雇主拒收：明细「已托管 → 冻结」，等待争议裁决（FR-ACCEPT.RESULT.02）。
     *
     * @param slotId 名额 ID
     */
    @Transactional
    public void freezeForDispute(Long slotId) {
        transitEscrowDetail(slotId, EscrowDetailStatus.ESCROW_DETAIL_HELD,
                EscrowDetailStateMachine.Event.DISPUTE, "托管明细争议冻结");
    }

    /**
     * 争议撤回 / 裁决放款前置：明细「冻结 → 已托管」（V6，FR-DISPUTE.WITHDRAW.01）。
     *
     * <p>放款路径随后复用验收结算，而结算要求明细为「已托管」，因此裁决放款与撤回
     * 都先解冻回「已托管」，再各自推进。</p>
     *
     * @param slotId 名额 ID
     */
    @Transactional
    public void unfreezeAfterDispute(Long slotId) {
        transitEscrowDetail(slotId, EscrowDetailStatus.ESCROW_DETAIL_FROZEN,
                EscrowDetailStateMachine.Event.DISPUTE_UNFREEZE, "托管明细争议解冻");
    }

    /**
     * 争议裁决退款：明细「冻结 → 已退款」，钱退回雇主（V6，FR-DISPUTE.RESOLVE.01）。
     *
     * <p>记账幂等键用争议单 ID：同一争议单只可能退款一次，且与撤单 / 到期退款的
     * 任务号幂等键不会冲突。</p>
     *
     * @param slotId    名额 ID
     * @param disputeId 争议单 ID（记账幂等键第二段）
     * @param employerId 雇主用户 ID
     * @return 退款金额（分）
     * @throws IllegalStateException 明细不存在或不在冻结状态
     */
    @Transactional
    public long refundFrozenSlot(Long slotId, Long disputeId, Long employerId) {
        FundEscrowDetail detail = fundEscrowDetailMapper.selectOne(
                new LambdaQueryWrapper<FundEscrowDetail>().eq(FundEscrowDetail::getSlotId, slotId));
        if (detail == null) {
            throw new IllegalStateException("托管明细不存在 slotId=" + slotId);
        }
        int updated = fundEscrowDetailMapper.update(null, new LambdaUpdateWrapper<FundEscrowDetail>()
                .eq(FundEscrowDetail::getId, detail.getId())
                .eq(FundEscrowDetail::getStatus, EscrowDetailStatus.ESCROW_DETAIL_FROZEN)
                .set(FundEscrowDetail::getStatus,
                        EscrowDetailStateMachine.transit(EscrowDetailStatus.ESCROW_DETAIL_FROZEN,
                                EscrowDetailStateMachine.Event.DISPUTE_REFUND)));
        if (updated != 1) {
            throw new IllegalStateException("托管明细不在冻结状态，无法裁决退款 slotId=" + slotId);
        }

        long amount = detail.getAmount();
        FundAccountService.Transfer transfer = fundAccountService.refund(employerId, amount);
        String bizNo = String.valueOf(disputeId);
        journalService.post(JournalBizType.ESCROW_REFUND, bizNo,
                JournalEventType.ESCROW_REFUND, JournalTriggerType.OPERATOR, "争议裁决退款",
                List.of(
                        new JournalService.EntryDraft(transfer.debitAccountId(),
                                transfer.debitSubject(), JournalDirection.DEBIT, amount),
                        new JournalService.EntryDraft(transfer.creditAccountId(),
                                transfer.creditSubject(), JournalDirection.CREDIT, amount)));
        fundLedgerGuard.assertBalanced("DISPUTE_REFUND", bizNo);
        closeOrderIfAllDetailsTerminal(detail.getEscrowOrderId());
        log.warn("争议裁决退款 slotId={} disputeId={} employerId={} amount={}",
                slotId, disputeId, employerId, amount);
        return amount;
    }

    /**
     * 条件推进一条托管明细，0 行即状态与调用方判断不一致，抛异常回滚整个事务。
     */
    private void transitEscrowDetail(Long slotId, EscrowDetailStatus from,
                                     EscrowDetailStateMachine.Event event, String action) {
        FundEscrowDetail detail = fundEscrowDetailMapper.selectOne(
                new LambdaQueryWrapper<FundEscrowDetail>().eq(FundEscrowDetail::getSlotId, slotId));
        if (detail == null) {
            throw new IllegalStateException("托管明细不存在 slotId=" + slotId);
        }
        int updated = fundEscrowDetailMapper.update(null, new LambdaUpdateWrapper<FundEscrowDetail>()
                .eq(FundEscrowDetail::getId, detail.getId())
                .eq(FundEscrowDetail::getStatus, from)
                .set(FundEscrowDetail::getStatus,
                        EscrowDetailStateMachine.transit(from, event)));
        if (updated != 1) {
            throw new IllegalStateException(action + "失败，明细不在" + from + " slotId=" + slotId);
        }
        closeOrderIfAllDetailsTerminal(detail.getEscrowOrderId());
        log.info("{} slotId={} detailId={}", action, slotId, detail.getId());
    }

    /**
     * 查询任务托管总额（FR-TASK.DETAIL.01，v2-c6）。
     *
     * <p>返回发布时快照的托管总额，供 task 域详情投影使用。口径是「这个任务托管了多少钱」，
     * 不是实时剩余余额：撤单 / 到期退款后总额不变。任务尚未发布（草稿）或托管单不存在时
     * 返回 {@code null}，<b>不返回 0</b>——0 会被前端渲染成「已托管 0 元」。</p>
     *
     * <p>只读、无副作用，故不加 {@code @Transactional}。</p>
     *
     * @param taskId 任务 ID
     * @return 托管总额（分）；无托管单返回 null
     * @since V2
     */
    public Long totalAmountOf(Long taskId) {
        FundEscrowOrder order = fundEscrowOrderMapper.selectOne(new LambdaQueryWrapper<FundEscrowOrder>()
                .eq(FundEscrowOrder::getTaskId, taskId));
        return order == null ? null : order.getTotalAmount();
    }

    /**
     * 明细全部进入终态时关闭托管单；仍有余量则保持已托管。
     */
    private void closeOrderIfAllDetailsTerminal(Long escrowOrderId) {
        Long remaining = fundEscrowDetailMapper.selectCount(new LambdaQueryWrapper<FundEscrowDetail>()
                .eq(FundEscrowDetail::getEscrowOrderId, escrowOrderId)
                .in(FundEscrowDetail::getStatus,
                        EscrowDetailStatus.ESCROW_DETAIL_PENDING_HOLD,
                        EscrowDetailStatus.ESCROW_DETAIL_HELD,
                        EscrowDetailStatus.ESCROW_DETAIL_RELEASING,
                        EscrowDetailStatus.ESCROW_DETAIL_FROZEN));
        if (remaining != null && remaining > 0) {
            return;
        }
        fundEscrowOrderMapper.update(null, new LambdaUpdateWrapper<FundEscrowOrder>()
                .eq(FundEscrowOrder::getId, escrowOrderId)
                .eq(FundEscrowOrder::getStatus, EscrowOrderStatus.ESCROW_ORDER_HELD)
                .set(FundEscrowOrder::getStatus,
                        EscrowOrderStateMachine.transit(EscrowOrderStatus.ESCROW_ORDER_HELD,
                                EscrowOrderStateMachine.Event.CLOSE)));
    }
}
