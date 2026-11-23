package com.escrow.platform.settlement.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.domain.Settlement;
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
 * 打款自动重试（FR-PAY.ISSUE.02 / CR-13）。
 *
 * <p><b>每次尝试一张新打款单、一个新请求号</b>：同一请求号重发在通道侧可能被去重，
 * 看起来「重试了」实际没有出款；换号才能保证重试真实发生，同时保留每次尝试的完整历史。</p>
 *
 * <p>重试额度由 {@link PayoutRuntimeConfig#maxAttempts()} 决定；超额时
 * {@code PayoutTxService} 已把上一张单推进「待人工」，不会留下待重试的结算单，
 * 本服务仍做一次防御性检查。</p>
 *
 * @since V5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutRetryService {

    /** 单次扫描上限。 */
    private static final int MAX_SCAN = 100;

    private final SettlementMapper settlementMapper;
    private final PayoutOrderMapper payoutOrderMapper;
    private final PayoutRuntimeConfig runtimeConfig;

    /**
     * 查询到期待重试的结算单。
     *
     * @param limit 本次最多返回数量（1~{@value #MAX_SCAN}）
     * @return 结算单 ID，按更新时间升序
     */
    public List<Long> findRetryableSettlementIds(int limit) {
        int safeLimit = Math.max(1, Math.min(MAX_SCAN, limit));
        List<Settlement> settlements = settlementMapper.selectList(
                new LambdaQueryWrapper<Settlement>()
                        .select(Settlement::getId)
                        .eq(Settlement::getStatus, SettlementStatus.SETTLEMENT_PENDING_RETRY)
                        .le(Settlement::getUpdatedAt, runtimeConfig.retryCutoff())
                        .orderByAsc(Settlement::getUpdatedAt)
                        .last("LIMIT " + safeLimit));
        return settlements.stream().map(Settlement::getId).toList();
    }

    /**
     * 为待重试结算单新建下一次尝试。
     *
     * @param settlementId 结算单 ID
     * @return 新打款单 ID；不满足重试条件时返回 {@code null}
     */
    @Transactional
    public Long startNextAttempt(Long settlementId) {
        Settlement settlement = settlementMapper.selectById(settlementId);
        if (settlement == null || settlement.getStatus() != SettlementStatus.SETTLEMENT_PENDING_RETRY) {
            log.info("结算单不在待重试，跳过 settlementId={} status={}", settlementId,
                    settlement == null ? null : settlement.getStatus());
            return null;
        }
        PayoutOrder last = payoutOrderMapper.selectOne(
                new LambdaQueryWrapper<PayoutOrder>()
                        .eq(PayoutOrder::getSettlementId, settlementId)
                        .orderByDesc(PayoutOrder::getAttemptSeq)
                        .last("LIMIT 1"));
        int nextSeq = last == null ? 1 : last.getAttemptSeq() + 1;
        if (nextSeq > runtimeConfig.maxAttempts()) {
            log.error("重试额度已用尽，不再新建尝试 settlementId={} nextSeq={} max={}",
                    settlementId, nextSeq, runtimeConfig.maxAttempts());
            return null;
        }
        PayoutOrder payout = new PayoutOrder();
        payout.setSettlementId(settlementId);
        payout.setRequestNo("PO" + IdWorker.getId());
        payout.setAttemptSeq(nextSeq);
        payout.setAmount(settlement.getNetAmount());
        payout.setStatus(PayoutStatus.PAYOUT_PENDING_EXECUTE);
        payout.setStatusEnteredAt(LocalDateTime.now());
        payoutOrderMapper.insert(payout);

        int rows = settlementMapper.update(null, new LambdaUpdateWrapper<Settlement>()
                .eq(Settlement::getId, settlementId)
                .eq(Settlement::getStatus, SettlementStatus.SETTLEMENT_PENDING_RETRY)
                .set(Settlement::getStatus,
                        SettlementStateMachine.transit(SettlementStatus.SETTLEMENT_PENDING_RETRY,
                                SettlementStateMachine.Event.RETRY_START))
                .set(Settlement::getUpdatedAt, LocalDateTime.now()));
        if (rows != 1) {
            throw new IllegalStateException("结算单重试跃迁失败 settlementId=" + settlementId);
        }
        log.warn("打款第 {} 次尝试已创建 payoutId={} settlementId={} requestNo={}",
                nextSeq, payout.getId(), settlementId, payout.getRequestNo());
        return payout.getId();
    }
}
