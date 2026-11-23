package com.escrow.platform.ai.infrastructure.llm;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内查询缓存（V10-c1）：带 TTL 的简单实现，不引 Caffeine 之外的依赖。
 *
 * <p>为什么任务详情用 Caffeine、这里不用：查询缓存的键是"整段用户输入"，
 * 基数大、命中率低、过期即弃。上 Caffeine 的淘汰策略收益很小，
 * 但会让人误以为这里需要本地缓存调优。</p>
 *
 * @since V10
 */
public class InMemoryAiQueryCache implements AiQueryCache {

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void put(String key, String value, long ttlSeconds) {
        if (value == null || ttlSeconds <= 0) {
            return;
        }
        entries.put(key, new Entry(value, Instant.now().plusSeconds(ttlSeconds)));
    }

    @Override
    public void clear() {
        entries.clear();
    }

    private record Entry(String value, Instant expiresAt) {
    }
}
