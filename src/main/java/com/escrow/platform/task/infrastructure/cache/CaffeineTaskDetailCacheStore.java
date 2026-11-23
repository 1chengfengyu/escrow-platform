package com.escrow.platform.task.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.stereotype.Component;

/**
 * 任务详情缓存的本地 Caffeine 层（v2-c4，docs/decisions.md #49）。
 *
 * <p>只存快照，不存空值标记：负结果只写 Redis（卡片 §4.3），本地层不缓存「不存在」，
 * 避免本地与远端删除时序不一致时把不存在的结论留得更久。</p>
 *
 * <p>TTL 与条目上限由 {@code CacheConfig} 的 Caffeine 实例统一控制（60 秒 + 抖动），
 * 因此本类不解析传入的 {@code ttlSeconds}。</p>
 *
 * @since V2
 */
@Component("caffeineTaskDetailCacheStore")
public class CaffeineTaskDetailCacheStore implements TaskDetailCacheStore {

    private final Cache<Long, Object> localCache;

    public CaffeineTaskDetailCacheStore(Cache<Long, Object> localCache) {
        this.localCache = localCache;
    }

    @Override
    public Lookup get(Long taskId) {
        Object value = localCache.getIfPresent(taskId);
        if (value instanceof TaskDetailSnapshot snapshot) {
            return Lookup.present(snapshot);
        }
        return Lookup.miss();
    }

    @Override
    public void put(Long taskId, TaskDetailSnapshot snapshot, long ttlSeconds) {
        localCache.put(taskId, snapshot);
    }

    @Override
    public void putMissing(Long taskId, long ttlSeconds) {
        // 负结果只落 Redis（卡片 §4.3）
    }

    @Override
    public void invalidate(Long taskId) {
        localCache.invalidate(taskId);
    }
}
