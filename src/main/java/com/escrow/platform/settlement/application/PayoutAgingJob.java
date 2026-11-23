package com.escrow.platform.settlement.application;

import com.escrow.platform.common.web.TraceContext;
import com.escrow.platform.settlement.api.PayoutAgingAlert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 账龄告警任务（V5，FR-JOB.AGING.01）。
 *
 * <p>告警口径只用 {@code status_entered_at}：待查证 / 待人工单据在当前状态停留超过
 * {@code escrow.payout.aging-warning-minutes}（默认 30 分钟）就报一条 error 日志。
 * 本版不接外部告警通道，日志即告警出口，V6 对账任务会复用同一口径。</p>
 *
 * @since V5
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class PayoutAgingJob {

    private final PayoutAdminService payoutAdminService;

    /** 每 5 分钟检查一次。 */
    @Scheduled(fixedDelayString = "${escrow.job.aging-scan-interval-ms:300000}",
            initialDelayString = "${escrow.job.aging-scan-initial-delay-ms:60000}")
    public void scan() {
        TraceContext.runWithNewTrace("payout-aging", this::scanOnce);
    }

    private void scanOnce() {
        // 账龄是管理口径，任务内部不走 HTTP 权限；直接复用同一查询实现
        List<PayoutAgingAlert> alerts = payoutAdminService.agingForSystem();
        if (alerts.isEmpty()) {
            return;
        }
        log.error("打款账龄告警 count={} 最久 payoutId={} ageMinutes={}",
                alerts.size(), alerts.get(0).payoutId(), alerts.get(0).ageMinutes());
        for (PayoutAgingAlert alert : alerts) {
            log.warn("账龄告警 payoutId={} settlementId={} status={} amount={} ageMinutes={}",
                    alert.payoutId(), alert.settlementId(), alert.status(),
                    alert.amount(), alert.ageMinutes());
        }
    }
}
