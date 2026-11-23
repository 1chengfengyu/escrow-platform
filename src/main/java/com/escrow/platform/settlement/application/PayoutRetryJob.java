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
 * 打款重试任务（FR-PAY.ISSUE.02 / FR-JOB.LOCK.01）。
 *
 * <p>与出站任务分工：出站任务只处理「待执行」，本任务只处理「待重试」。
 * 重试同样按结算单加锁，多实例下不会重复创建同一笔尝试。</p>
 *
 * @since V5
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class PayoutRetryJob {

    private static final int SCAN_LIMIT = 100;

    private final PayoutRetryService payoutRetryService;
    private final LockingTaskExecutor lockingTaskExecutor;

    /** 每 30 秒扫描一次（可用 {@code escrow.job.retry-scan-interval-ms} 覆盖）。 */
    @Scheduled(fixedDelayString = "${escrow.job.retry-scan-interval-ms:30000}",
            initialDelayString = "${escrow.job.retry-scan-initial-delay-ms:45000}")
    public void scan() {
        TraceContext.runWithNewTrace("payout-retry", this::scanOnce);
    }

    private void scanOnce() {
        List<Long> settlementIds = payoutRetryService.findRetryableSettlementIds(SCAN_LIMIT);
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (Long settlementId : settlementIds) {
            try {
                AtomicBoolean executed = new AtomicBoolean(false);
                lockingTaskExecutor.executeWithLock((Runnable) () -> {
                    executed.set(true);
                    payoutRetryService.startNextAttempt(settlementId);
                }, new LockConfiguration(Instant.now(), "payout-retry-" + settlementId,
                        Duration.ofMinutes(2), Duration.ZERO));
                if (executed.get()) {
                    created++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException ex) {
                failed++;
                log.warn("打款重试任务失败 settlementId={}", settlementId, ex);
            }
        }
        if (!settlementIds.isEmpty()) {
            log.info("重试扫描完成 pending={} created={} skipped={} failed={}",
                    settlementIds.size(), created, skipped, failed);
        }
    }
}
