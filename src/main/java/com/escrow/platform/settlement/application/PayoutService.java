package com.escrow.platform.settlement.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.settlement.domain.ChannelOutcome;
import com.escrow.platform.settlement.domain.ChannelResult;
import com.escrow.platform.settlement.domain.PaymentChannel;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 打款出站编排（SRS A.4 T2）：通道调用严格在事务外。
 *
 * <p>流程固定为「事务 1 抢占并落执行中 → 事务外调用通道 → 事务 2 落结果」。
 * 通道抛异常按 TIMEOUT 处理，绝不按失败重试（规则 §6：未知不能当失败）。</p>
 *
 * <p>V5 增加两条分支：通道成功但<b>金额不一致</b>转人工（不允许按请求金额落账，
 * 也不允许按实际金额自动落账）；通道要求<b>重复回调</b>时再调一次 T3，
 * 由状态机与条件更新把第二次吞掉，用于证明回调幂等。</p>
 *
 * @since V4
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutService {

    /** 单次扫描上限，避免一次把全部积压读进内存。 */
    private static final int MAX_SCAN = 100;

    private final PayoutTxService payoutTxService;
    private final PaymentChannel paymentChannel;
    private final PayoutOrderMapper payoutOrderMapper;
    private final PayoutRuntimeConfig runtimeConfig;

    /**
     * 对一张打款单执行 T2 + T3。
     *
     * @param payoutId 打款单 ID
     * @return 本次是否已确认打款成功（未知态、失败、未抢到执行权均为 {@code false}）
     */
    public boolean dispatch(Long payoutId) {
        PayoutTxService.PayoutDispatch dispatch = payoutTxService.claimForDispatch(payoutId);
        if (dispatch == null) {
            return false;
        }
        ChannelOutcome outcome;
        try {
            outcome = paymentChannel.pay(dispatch.requestNo(), dispatch.amount());
        } catch (RuntimeException ex) {
            log.error("通道调用异常，按未知处理 payoutId={} requestNo={}",
                    payoutId, dispatch.requestNo(), ex);
            outcome = ChannelOutcome.of(ChannelResult.TIMEOUT);
        }
        try {
            switch (outcome.result()) {
                case SUCCESS -> {
                    if (outcome.matches(dispatch.amount())) {
                        payoutTxService.handleSuccess(payoutId, outcome.actualAmount());
                        if (outcome.duplicateCallback()) {
                            // 模拟通道重复投递：第二次必须被状态机与条件更新忽略，不得二次分账
                            log.warn("模拟重复回调，再次落库 payoutId={} requestNo={}",
                                    payoutId, dispatch.requestNo());
                            payoutTxService.handleSuccess(payoutId, outcome.actualAmount());
                        }
                        return true;
                    } else {
                        payoutTxService.handleAmountMismatch(payoutId, outcome.actualAmount());
                    }
                }
                case FAIL -> payoutTxService.handleFail(payoutId);
                case TIMEOUT, LOST_RESPONSE -> payoutTxService.handleUnknown(payoutId, outcome.result());
            }
        } catch (RuntimeException ex) {
            // 通道可能已经出款，事务回滚后打款单仍是执行中；交给调度补偿转待查证，禁止重发。
            log.error("打款结果落库失败，留在执行中等待补偿 payoutId={}", payoutId, ex);
        }
        return false;
    }

    /**
     * 查询「待执行」超期的打款单 ID（扫描本身不加锁，锁在单据上，FR-JOB.LOCK.01）。
     *
     * @param limit 本次最多返回数量（1~{@value #MAX_SCAN}）
     * @return 打款单 ID 列表，按账龄升序
     */
    public List<Long> findDispatchableIds(int limit) {
        List<PayoutOrder> orders = payoutOrderMapper.selectList(
                new LambdaQueryWrapper<PayoutOrder>()
                        .select(PayoutOrder::getId)
                        .eq(PayoutOrder::getStatus, PayoutStatus.PAYOUT_PENDING_EXECUTE)
                        .le(PayoutOrder::getStatusEnteredAt, runtimeConfig.pendingCutoff())
                        .orderByAsc(PayoutOrder::getStatusEnteredAt)
                        .last("LIMIT " + safeLimit(limit)));
        return orders.stream().map(PayoutOrder::getId).toList();
    }

    /**
     * 扫描「执行中」超时并转待查证（不重发）。
     *
     * @param limit 本次最多处理数量（1~{@value #MAX_SCAN}）
     * @return 实际转为待查证的数量
     */
    public int recoverStuck(int limit) {
        List<PayoutOrder> orders = payoutOrderMapper.selectList(
                new LambdaQueryWrapper<PayoutOrder>()
                        .select(PayoutOrder::getId)
                        .eq(PayoutOrder::getStatus, PayoutStatus.PAYOUT_EXECUTING)
                        .le(PayoutOrder::getStatusEnteredAt, runtimeConfig.stuckCutoff())
                        .orderByAsc(PayoutOrder::getStatusEnteredAt)
                        .last("LIMIT " + safeLimit(limit)));
        int recovered = 0;
        for (PayoutOrder order : orders) {
            if (payoutTxService.markStuckUnknown(order.getId(), runtimeConfig.stuckCutoff())) {
                recovered++;
            }
        }
        return recovered;
    }

    private static int safeLimit(int limit) {
        return Math.max(1, Math.min(MAX_SCAN, limit));
    }
}
