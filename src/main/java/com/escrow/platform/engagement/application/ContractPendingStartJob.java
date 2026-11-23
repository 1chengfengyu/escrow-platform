package com.escrow.platform.engagement.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 未开始自动取消扫描（FR-JOB.START.01）。
 *
 * <p>只负责「找 ID + 逐个调用 {@link ContractService#autoCancel(Long)}」；
 * 判定与三写动作全部在 Service 里，与手动取消共用同一实现（FR-SIGN.START.02）。</p>
 *
 * <p>{@code @Profile("!test")}：单测与集成测试不跑定时任务。单个签约失败只记日志并继续，
 * 不能让一个坏签约挡住整批；每次执行按 FR-JOB.LOG.01 记录处理 / 成功 / 失败条数。</p>
 *
 * @since V3
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ContractPendingStartJob {

    private final ContractService contractService;

    /** 每 10 分钟扫描一次（可用 {@code escrow.contract.pending-start-scan-interval-ms} 覆盖）。 */
    @Scheduled(fixedDelayString = "${escrow.contract.pending-start-scan-interval-ms:600000}",
            initialDelayString = "${escrow.contract.pending-start-scan-initial-delay-ms:10000}")
    @SchedulerLock(name = "contract-pending-start-scan", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void scan() {
        List<Long> contractIds = contractService.findOverdueContractIds();
        int succeeded = 0;
        int failed = 0;
        for (Long contractId : contractIds) {
            try {
                if (contractService.autoCancel(contractId)) {
                    succeeded++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("未开始自动取消失败 contractId={}", contractId, e);
            }
        }
        log.info("未开始自动取消扫描完成 processed={} succeeded={} failed={}",
                contractIds.size(), succeeded, failed);
    }
}
