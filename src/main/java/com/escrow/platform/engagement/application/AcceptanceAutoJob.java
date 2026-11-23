package com.escrow.platform.engagement.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 交付满 48 小时自动验收（FR-ACCEPT.AUTO.01、JOB.ACCEPT.01）。
 *
 * <p>只负责「找 ID + 逐个调用 {@link AcceptanceService#autoAccept(Long)}」；
 * 判定、结算、打款全部复用人工验收入口，不另写一套。{@code trigger_source} 在
 * {@link AcceptanceTxService} 内记为 SYSTEM。</p>
 *
 * <p>{@code @Profile("!test")}：测试不跑定时任务。同一签约被人工 / 自动同时处理时，
 * 条件更新只有一个成功，另一个返回 {@code null} 直接跳过。</p>
 *
 * @since V4
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class AcceptanceAutoJob {

    private final ContractQueryService contractQueryService;
    private final AcceptanceService acceptanceService;

    /** 自动验收阈值（小时）；SRS 默认 48，配置项见 CR-13。 */
    @Value("${escrow.accept.auto-hours:48}")
    private long autoHours;

    /** 每小时扫描一次（可用 {@code escrow.accept.auto-scan-interval-ms} 覆盖）。 */
    @Scheduled(fixedDelayString = "${escrow.accept.auto-scan-interval-ms:3600000}",
            initialDelayString = "${escrow.accept.auto-scan-initial-delay-ms:20000}")
    @SchedulerLock(name = "auto-acceptance-scan", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void scan() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(autoHours);
        List<Long> contractIds = contractQueryService.findOverdueDeliveredContractIds(cutoff, 100);
        int accepted = 0;
        int failed = 0;
        for (Long contractId : contractIds) {
            try {
                if (acceptanceService.autoAccept(contractId) != null) {
                    accepted++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("自动验收失败 contractId={}", contractId, e);
            }
        }
        log.info("自动验收扫描完成 processed={} accepted={} failed={}",
                contractIds.size(), accepted, failed);
    }
}
