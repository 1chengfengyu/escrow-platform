package com.escrow.platform.dispute.application;

import com.escrow.platform.common.web.TraceContext;
import com.escrow.platform.dispute.api.DisputeSummaryView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 争议超期提示任务（V6，FR-DISPUTE.CLOSE.02）。
 *
 * <p>待裁决超过 {@code escrow.job.dispute-overdue-days}（默认 7 天）的争议每轮报 error 日志，
 * 并逐条列出等待天数；管理端 {@code GET /api/admin/disputes/overdue} 提供同一口径的统计。
 * 本版不接外部告警通道，日志即提示出口。</p>
 *
 * @since V6
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class DisputeTimeoutJob {

    private final DisputeService disputeService;

    /** 每小时扫描一次超期争议。 */
    @Scheduled(fixedDelayString = "${escrow.job.dispute-overdue-scan-interval-ms:3600000}",
            initialDelayString = "${escrow.job.dispute-overdue-scan-initial-delay-ms:120000}")
    public void scan() {
        TraceContext.runWithNewTrace("dispute-overdue", this::scanOnce);
    }

    private void scanOnce() {
        int days = 7;
        List<DisputeSummaryView> overdue = disputeService.overdueForSystem(days);
        if (overdue.isEmpty()) {
            return;
        }
        log.error("争议超期告警 count={} 最久 disputeId={} pendingDays={}",
                overdue.size(), overdue.get(0).id(), overdue.get(0).pendingDays());
        for (DisputeSummaryView dispute : overdue) {
            log.warn("争议超期 disputeId={} slotId={} initiator={} pendingDays={}",
                    dispute.id(), dispute.slotId(), dispute.initiatorType(), dispute.pendingDays());
        }
    }
}
