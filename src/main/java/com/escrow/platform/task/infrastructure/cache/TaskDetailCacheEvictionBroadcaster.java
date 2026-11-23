package com.escrow.platform.task.infrastructure.cache;

/**
 * 任务详情缓存失效广播（V9，多实例）。
 *
 * <p>{@link TaskDetailCache#evict(Long)} 只清本实例 Caffeine 与 Redis；其他实例的
 * 本地层最长会脏 60 秒（{@code CacheConfig.LOCAL_TTL_SECONDS}）。evict 时广播一条，
 * 各实例订阅后清自己的本地层。</p>
 *
 * <p>做成单方法接口是为了单测能直接用 lambda 传一个空实现，不必 mock 整个组件。</p>
 *
 * @since V9
 */
@FunctionalInterface
public interface TaskDetailCacheEvictionBroadcaster {

    /** 广播频道；消息体为任务 ID 的十进制字符串。 */
    String CHANNEL = "escrow:cache:task-detail:evict";

    /**
     * 广播某个任务的失效事件。
     *
     * @param taskId 任务 ID
     */
    void broadcast(Long taskId);
}
