package com.escrow.platform.task.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 任务到期扫描（FR-TASK.EXPIRE.01）。
 *
 * <p>只负责「找 ID + 逐个调用 {@link TaskExpiryService#expireTask(Long)}」；
 * 判定与资金动作全部在 Service 里，与手动收口入口共用同一实现（用户裁决 ④）。</p>
 *
 * <p>{@code @Profile("!test")}：单测与集成测试不跑定时任务，避免测试互相干扰。
 * 单个任务失败只记日志并继续，不能让一个坏任务挡住整批。</p>
 *
 * @since V2
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class TaskExpiryJob {

    private final TaskExpiryService taskExpiryService;

    /** 周期性扫描到期任务；间隔可用 {@code escrow.task.expiry-scan-interval-ms} 覆盖。 */
    @Scheduled(fixedDelayString = "${escrow.task.expiry-scan-interval-ms:60000}",
            initialDelayString = "${escrow.task.expiry-scan-initial-delay-ms:10000}")
    @SchedulerLock(name = "task-expiry-scan", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void scan() {
        List<Long> taskIds = taskExpiryService.findOverdueTaskIds();
        for (Long taskId : taskIds) {
            try {
                taskExpiryService.expireTask(taskId);
            } catch (RuntimeException e) {
                log.warn("任务到期收口失败 taskId={}", taskId, e);
            }
        }
    }
}
