package com.escrow.platform.task.application;

import com.escrow.platform.task.infrastructure.cache.TaskDetailCache;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 任务详情缓存的「提交后失效」帮助类（Cache-Aside）。
 *
 * <p>统一口径：先更库、事务提交后 evict；不在事务中（如纯单测）立即 evict。
 * 发布 / 撤单 / 到期三处必须走同一段逻辑，避免某一处漏删缓存或改成「先删后更」。</p>
 *
 * @since V2
 */
public final class TaskCacheEviction {

    private TaskCacheEviction() {
    }

    /**
     * 在当前事务提交后清理缓存；无事务时立即清理。
     *
     * @param cache  任务详情缓存
     * @param taskId 任务 ID
     */
    public static void afterCommit(TaskDetailCache cache, Long taskId) {
        Runnable evict = () -> cache.evict(taskId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict.run();
                }
            });
        } else {
            evict.run();
        }
    }
}
