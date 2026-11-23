package com.escrow.platform.task.infrastructure.cache;

import com.escrow.platform.common.config.CacheConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 三级缓存编排单测（v2-c4 卡片 §6 / §8 第 7~10 条）。
 *
 * <p>两层都用 {@link InMemoryTaskDetailCacheStore} 伪实现，不连 Redis；
 * 覆盖：命中不重复回源、远端命中回填本地、驱逐后重新回源、空值只查一次 DB、
 * 空值标记可驱逐、单飞限制 DB 次数、Redis 抛异常降级、TTL 抖动上界。</p>
 *
 * @since V2
 */
class TaskDetailCacheTest {

    private InMemoryTaskDetailCacheStore local;
    private InMemoryTaskDetailCacheStore remote;
    private List<Long> broadcasts;
    private TaskDetailCache cache;

    @BeforeEach
    void setUp() {
        local = new InMemoryTaskDetailCacheStore();
        remote = new InMemoryTaskDetailCacheStore();
        broadcasts = new ArrayList<>();
        cache = new TaskDetailCache(local, remote, broadcasts::add);
    }

    /** 重复读同一任务只回源一次。 */
    @Test
    void repeatedReadsShouldHitLoaderOnce() {
        AtomicInteger loads = new AtomicInteger();
        Supplier<TaskDetailSnapshot> loader = () -> {
            loads.incrementAndGet();
            return snapshot("t");
        };

        assertThat(cache.get(1L, loader).title()).isEqualTo("t");
        assertThat(cache.get(1L, loader).title()).isEqualTo("t");

        assertThat(loads).hasValue(1);
    }

    /** 本地未命中、远端命中：不回源，并回填本地。 */
    @Test
    void remoteHitShouldBackfillLocalWithoutLoader() {
        remote.put(1L, snapshot("remote"), 60L);
        AtomicInteger loads = new AtomicInteger();

        TaskDetailSnapshot got = cache.get(1L, () -> {
            loads.incrementAndGet();
            return snapshot("db");
        });

        assertThat(got.title()).isEqualTo("remote");
        assertThat(loads).hasValue(0);
        assertThat(local.contains(1L)).isTrue();
    }

    /** 驱逐后下一次读必须重新回源。 */
    @Test
    void evictShouldForceReload() {
        AtomicInteger loads = new AtomicInteger();
        Supplier<TaskDetailSnapshot> loader = () -> {
            loads.incrementAndGet();
            return snapshot("t");
        };

        cache.get(1L, loader);
        cache.evict(1L);
        cache.get(1L, loader);

        assertThat(loads).hasValue(2);
        assertThat(local.contains(1L)).isTrue();
        assertThat(remote.contains(1L)).isTrue();
    }

    /** V9：驱逐必须广播，否则其他实例的本地层最长脏 60 秒。 */
    @Test
    void evictShouldBroadcastToOtherInstances() {
        cache.get(1L, () -> snapshot("t"));

        cache.evict(1L);

        assertThat(broadcasts).containsExactly(1L);
    }

    /** 不存在的任务：空值标记命中，100 次读只查一次 DB。 */
    @Test
    void missingTaskShouldBeQueriedOnce() {
        AtomicInteger loads = new AtomicInteger();
        Supplier<TaskDetailSnapshot> loader = () -> {
            loads.incrementAndGet();
            return null;
        };

        for (int i = 0; i < 100; i++) {
            assertThat(cache.get(9L, loader)).isNull();
        }

        assertThat(loads).hasValue(1);
    }

    /** 空值标记必须可被驱逐：驱逐后再查一次，DB 计数 +1。 */
    @Test
    void evictShouldClearMissingMarker() {
        AtomicInteger loads = new AtomicInteger();
        Supplier<TaskDetailSnapshot> loader = () -> {
            loads.incrementAndGet();
            return null;
        };

        assertThat(cache.get(9L, loader)).isNull();
        cache.evict(9L);
        assertThat(cache.get(9L, loader)).isNull();

        assertThat(loads).hasValue(2);
    }

    /** 同 id 10 线程并发回源：DB 次数不超过 2（单飞 + 200ms 兜底）。 */
    @Test
    void concurrentMissesShouldLoadAtMostTwice() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        Supplier<TaskDetailSnapshot> loader = () -> {
            loads.incrementAndGet();
            try {
                Thread.sleep(120L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return snapshot("slow");
        };

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TaskDetailSnapshot>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return cache.get(7L, loader);
                }));
            }
            start.countDown();
            for (Future<TaskDetailSnapshot> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS).title()).isEqualTo("slow");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(loads.get()).isLessThanOrEqualTo(2);
    }

    /** Redis 层抛异常：仍返回数据，降级回源。 */
    @Test
    void remoteFailureShouldFallBackToLoader() {
        TaskDetailCache degrading =
                new TaskDetailCache(local, new ThrowingTaskDetailCacheStore(), taskId -> { });
        AtomicInteger loads = new AtomicInteger();

        TaskDetailSnapshot got = degrading.get(1L, () -> {
            loads.incrementAndGet();
            return snapshot("db");
        });

        assertThat(got.title()).isEqualTo("db");
        assertThat(loads).hasValue(1);
    }

    /** TTL 抖动返回值必须落在 [base, base*1.1)。 */
    @Test
    void ttlWithJitterShouldStayWithinBounds() {
        for (long base : new long[]{60L, 100L, 1800L}) {
            for (int i = 0; i < 2000; i++) {
                long ttl = CacheConfig.ttlWithJitter(base);
                assertThat(ttl).isGreaterThanOrEqualTo(base);
                assertThat(ttl).isLessThan(base + base / 10);
            }
        }
    }

    /** Redis 层自身 catch：连接异常时读返回未命中、写与删静默，不向调用方抛出。 */
    @Test
    @SuppressWarnings("unchecked")
    void redisStoreShouldSwallowRedisFailures() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        RedisTaskDetailCacheStore store = new RedisTaskDetailCacheStore(redis,
                new CacheConfig().taskDetailJsonCodec(new ObjectMapper()));

        assertThat(store.get(1L).hit()).isFalse();
        assertThatCode(() -> store.put(1L, snapshot("t"), 60L)).doesNotThrowAnyException();
        assertThatCode(() -> store.putMissing(1L, 60L)).doesNotThrowAnyException();
        assertThatCode(() -> store.invalidate(1L)).doesNotThrowAnyException();
    }

    private static TaskDetailSnapshot snapshot(String title) {
        return new TaskDetailSnapshot(
                1L, 1001L, title, "desc", "地推", 5000L, 2,
                LocalDateTime.of(2026, 12, 1, 9, 0),
                LocalDateTime.of(2026, 12, 2, 9, 0),
                "北京", "验收后结算",
                LocalDateTime.of(2026, 11, 20, 10, 0), null,
                10_000L, 2, 0, List.of());
    }

    /** 任意操作都抛异常的存储，模拟 Redis 不可用。 */
    private static final class ThrowingTaskDetailCacheStore implements TaskDetailCacheStore {

        @Override
        public Lookup get(Long taskId) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public void put(Long taskId, TaskDetailSnapshot snapshot, long ttlSeconds) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public void putMissing(Long taskId, long ttlSeconds) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public void invalidate(Long taskId) {
            throw new IllegalStateException("redis down");
        }
    }
}
