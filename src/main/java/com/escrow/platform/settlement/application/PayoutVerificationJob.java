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
 * 未知态查证任务（FR-PAY.UNKNOWN.01 / FR-JOB.LOCK.01）。
 *
 * <p>与打款调度同样的「扫描无锁、锁到单据」：同一张打款单在多实例下只会被一个实例查证。
 * 单次失败只记日志，不中断本轮其它单据。</p>
 *
 * @since V5
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class PayoutVerificationJob {

    private static final int SCAN_LIMIT = 100;

    private final PayoutVerificationService payoutVerificationService;
    private final LockingTaskExecutor lockingTaskExecutor;

    /** 每 30 秒扫描一次（可用 {@code escrow.job.verify-scan-interval-ms} 覆盖）。 */
    @Scheduled(fixedDelayString = "${escrow.job.verify-scan-interval-ms:30000}",
            initialDelayString = "${escrow.job.verify-scan-initial-delay-ms:45000}")
    public void scan() {
        TraceContext.runWithNewTrace("payout-verify", this::scanOnce);
    }

    private void scanOnce() {
        List<Long> payoutIds = payoutVerificationService.findVerifiableIds(SCAN_LIMIT);
        int verified = 0;
        int skipped = 0;
        int failed = 0;
        for (Long payoutId : payoutIds) {
            try {
                AtomicBoolean executed = new AtomicBoolean(false);
                lockingTaskExecutor.executeWithLock((Runnable) () -> {
                    executed.set(true);
                    payoutVerificationService.verify(payoutId);
                }, new LockConfiguration(Instant.now(), "payout-verify-" + payoutId,
                        Duration.ofMinutes(2), Duration.ZERO));
                if (executed.get()) {
                    verified++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException ex) {
                failed++;
                log.warn("查证任务失败 payoutId={}", payoutId, ex);
            }
        }
        if (!payoutIds.isEmpty()) {
            log.info("查证扫描完成 pending={} verified={} skipped={} failed={}",
                    payoutIds.size(), verified, skipped, failed);
        }
    }
}
