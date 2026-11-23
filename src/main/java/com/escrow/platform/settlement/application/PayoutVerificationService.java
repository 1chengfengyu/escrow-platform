package com.escrow.platform.settlement.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.escrow.platform.settlement.domain.ChannelVerification;
import com.escrow.platform.settlement.domain.ChannelVerificationResult;
import com.escrow.platform.settlement.domain.PaymentChannel;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.domain.PayoutVerificationRecord;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutVerificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 未知态查证（FR-PAY.UNKNOWN.01~.03）。
 *
 * <p><b>只查证，不重发</b>：本服务只调 {@link PaymentChannel#verify}，永不调 {@code pay}。
 * 五种查证结论分别处置：</p>
 * <ol>
 *   <li>{@code SUCCESS}：按请求金额完成分账；</li>
 *   <li>{@code FAIL}：判失败，有额度则转待重试；</li>
 *   <li>{@code NOT_FOUND}：通道确认没这笔，判失败，有额度则转待重试；</li>
 *   <li>{@code AMOUNT_MISMATCH}：转人工，记录通道实际金额；</li>
 *   <li>{@code ERROR}：查证本身失败，<b>保持待查证</b>，下一轮再查。</li>
 * </ol>
 *
 * <p>查证流水先落库再推进状态：即使后续事务回滚，也能看到「查过、结论是什么」，
 * 避免人工排查时没有证据。</p>
 *
 * @since V5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutVerificationService {

    /** 单次扫描上限。 */
    private static final int MAX_SCAN = 100;

    private final PayoutOrderMapper payoutOrderMapper;
    private final PayoutVerificationMapper payoutVerificationMapper;
    private final PaymentChannel paymentChannel;
    private final PayoutTxService payoutTxService;
    private final PayoutRuntimeConfig runtimeConfig;

    /**
     * 查询已到查证时间的待查证打款单。
     *
     * @param limit 本次最多返回数量（1~{@value #MAX_SCAN}）
     * @return 打款单 ID，按进入待查证时间升序
     */
    public List<Long> findVerifiableIds(int limit) {
        int safeLimit = Math.max(1, Math.min(MAX_SCAN, limit));
        List<PayoutOrder> orders = payoutOrderMapper.selectList(
                new LambdaQueryWrapper<PayoutOrder>()
                        .select(PayoutOrder::getId)
                        .eq(PayoutOrder::getStatus, PayoutStatus.PAYOUT_PENDING_VERIFICATION)
                        .le(PayoutOrder::getStatusEnteredAt, runtimeConfig.verifyCutoff())
                        .orderByAsc(PayoutOrder::getStatusEnteredAt)
                        .last("LIMIT " + safeLimit));
        return orders.stream().map(PayoutOrder::getId).toList();
    }

    /**
     * 查证一张打款单并按其结论推进。
     *
     * @param payoutId 打款单 ID
     * @return 查证结论；打款单不在待查证时返回 {@code null}
     */
    public ChannelVerification verify(Long payoutId) {
        PayoutOrder payout = payoutOrderMapper.selectById(payoutId);
        if (payout == null) {
            log.warn("查证时打款单不存在 payoutId={}", payoutId);
            return null;
        }
        if (payout.getStatus() != PayoutStatus.PAYOUT_PENDING_VERIFICATION) {
            log.info("打款单不在待查证，跳过 payoutId={} status={}", payoutId, payout.getStatus());
            return null;
        }
        ChannelVerificationResult result;
        try {
            result = paymentChannel.verify(payout.getRequestNo(), payout.getAmount());
        } catch (RuntimeException ex) {
            log.error("查证调用异常，保持待查证 payoutId={} requestNo={}",
                    payoutId, payout.getRequestNo(), ex);
            result = ChannelVerificationResult.error();
        }
        record(payout, result);
        switch (result.result()) {
            case SUCCESS -> payoutTxService.handleVerifiedSuccess(payoutId);
            case FAIL, NOT_FOUND -> payoutTxService.handleVerificationFailed(payoutId);
            case AMOUNT_MISMATCH ->
                    payoutTxService.handleVerifiedAmountMismatch(payoutId, result.actualAmount());
            case ERROR -> log.warn("查证未成功，下一轮重试 payoutId={}", payoutId);
        }
        return result.result();
    }

    private void record(PayoutOrder payout, ChannelVerificationResult result) {
        PayoutVerificationRecord record = new PayoutVerificationRecord();
        record.setId(IdWorker.getId());
        record.setPayoutId(payout.getId());
        record.setRequestNo(payout.getRequestNo());
        record.setResult(result.result());
        record.setActualAmount(result.actualAmount());
        record.setCreatedAt(LocalDateTime.now());
        payoutVerificationMapper.insert(record);
        log.info("查证完成 payoutId={} requestNo={} result={} actual={}",
                payout.getId(), payout.getRequestNo(), result.result(), result.actualAmount());
    }
}
