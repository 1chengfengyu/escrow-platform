package com.escrow.platform.task.infrastructure.cache;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存存储的内存伪实现（v2-c4 测试专用）。
 *
 * <p>用 {@link ConcurrentHashMap} 代替 Redis / Caffeine，测试不依赖真 Redis（卡片 §4.6）。
 * 空值标记用私有哨兵对象表示，行为与 {@code RedisTaskDetailCacheStore} 的
 * {@code __NULL__} 一致：可被 {@link #invalidate(Long)} 清除。</p>
 *
 * @since V2
 */
public class InMemoryTaskDetailCacheStore implements TaskDetailCacheStore {

    private static final Object NULL_MARKER = new Object();

    private final ConcurrentHashMap<Long, Object> data = new ConcurrentHashMap<>();

    @Override
    public Lookup get(Long taskId) {
        Object value = data.get(taskId);
        if (value == null) {
            return Lookup.miss();
        }
        if (value == NULL_MARKER) {
            return Lookup.nullMarker();
        }
        return Lookup.present((TaskDetailSnapshot) value);
    }

    @Override
    public void put(Long taskId, TaskDetailSnapshot snapshot, long ttlSeconds) {
        data.put(taskId, snapshot);
    }

    @Override
    public void putMissing(Long taskId, long ttlSeconds) {
        data.put(taskId, NULL_MARKER);
    }

    @Override
    public void invalidate(Long taskId) {
        data.remove(taskId);
    }

    /** 是否存在任意缓存项（快照或空值标记）。 */
    public boolean contains(Long taskId) {
        return data.containsKey(taskId);
    }
}
