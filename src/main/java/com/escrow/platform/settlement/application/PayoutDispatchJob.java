package com.escrow.platform.settlement.application;

import com.escrow.platform.common.web.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 打款调度补偿（FR-JOB.PAYOUT.01、JOB.LOCK.01）。
 *
 * <p><b>扫描无锁、锁到单据</b>：查询待执行 ID 不加锁；真正出站前用动态锁名
 * {@code payout-dispatch-{id}} 抢锁，多实例下同一张打款单只会有一个实例调用通道。
 * 锁最多 10 分钟，与单笔打款的超时窗口一致。</p>
 *
 * <p><b>执行中超时不重发</b>：超过 {@code escrow.payout.stuck-minutes} 仍停在执行中的，
 * 一律转「待查证」，由 V5 的查证任务处理，绝不重新出站（规则 §6）。</p>
 *
 * @since V4
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class PayoutDispatchJob {

    private static final int SCAN_LIMIT = 100;

    private final PayoutService payoutService;
    private final LockingTaskExecutor lockingTaskExecutor;

    /** 每分钟扫描一次（可用 {@code escrow.job.payout-scan-interval-ms} 覆盖）。 */
    @Scheduled(fixedDelayString = "${escrow.job.payout-scan-interval-ms:60000}",
            initialDelayString = "${escrow.job.payout-scan-initial-delay-ms:30000}")
    public void scan() {
        TraceContext.runWithNewTrace("payout-dispatch", this::scanOnce);
    }

    private void scanOnce() {
        List<Long> payoutIds = payoutService.findDispatchableIds(SCAN_LIMIT);
        int dispatched = 0;
        int skipped = 0;
        int failed = 0;
        for (Long payoutId : payoutIds) {
            try {
                AtomicBoolean executed = new AtomicBoolean(false);
                lockingTaskExecutor.executeWithLock((Runnable) () -> {
                    executed.set(true);
                    payoutService.dispatch(payoutId);
                }, new LockConfiguration(Instant.now(), "payout-dispatch-" + payoutId,
                        Duration.ofMinutes(10), Duration.ZERO));
                if (executed.get()) {
                    dispatched++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("打款调度补偿失败 payoutId={}", payoutId, e);
            }
        }
        int recovered = payoutService.recoverStuck(SCAN_LIMIT);
        if (dispatched > 0 || failed > 0 || recovered > 0 || skipped > 0) {
            log.info("打款调度扫描完成 pending={} dispatched={} skipped={} failed={} recovered={}",
                    payoutIds.size(), dispatched, skipped, failed, recovered);
        }
    }
}
