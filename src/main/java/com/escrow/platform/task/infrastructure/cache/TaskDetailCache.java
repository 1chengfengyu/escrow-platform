package com.escrow.platform.task.infrastructure.cache;

import com.escrow.platform.common.config.CacheConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 任务详情三级缓存（v2-c4，docs/decisions.md #49）。
 *
 * <p>读取顺序固定：本地 Caffeine → Redis → DB。命中上层即返回并回填下层。
 * 缓存对象是 {@link TaskDetailSnapshot}（与观察者无关），不是对外视图——
 * 观察者相关字段由调用方拿到快照后实时投影，避免不同观察者互相串结果。</p>
 *
 * <p>写侧不做：本类只暴露 {@link #evict(Long)} 给业务在事务提交后调用，
 * 采用 Cache-Aside「先更库、后删缓存」，不做延迟双删、不用声明式缓存注解。</p>
 *
 * <p>故障降级是硬约束：任意一层抛异常都 catch 成 warn 并继续向下/回源，
 * 缓存故障不得让详情接口返回 5xx（卡片 §4.5）。</p>
 *
 * <p>击穿保护：同一 {@code taskId} 的并发回源只放一个线程查 DB，其余等待结果；
 * 等待超过 200ms 的线程直接自行查 DB，避免被慢查询拖死。</p>
 *
 * @since V2
 */
@Slf4j
@Component
public class TaskDetailCache {

    /** Redis 快照基准 TTL：30 分钟。 */
    static final long REMOTE_SNAPSHOT_TTL_SECONDS = 1800L;

    /** Redis 空值基准 TTL：60 秒。 */
    static final long REMOTE_MISSING_TTL_SECONDS = 60L;

    /** 单飞等待上限：超过即自行回源。 */
    private static final long SINGLE_FLIGHT_WAIT_MILLIS = 200L;

    private final TaskDetailCacheStore localStore;
    private final TaskDetailCacheStore remoteStore;
    private final TaskDetailCacheEvictionBroadcaster evictionBroadcaster;

    /** 同 key 回源协调表：值为正在加载的 future。 */
    private final ConcurrentHashMap<Long, CompletableFuture<TaskDetailSnapshot>> inFlight =
            new ConcurrentHashMap<>();

    public TaskDetailCache(
            @Qualifier("caffeineTaskDetailCacheStore") TaskDetailCacheStore localStore,
            @Qualifier("redisTaskDetailCacheStore") TaskDetailCacheStore remoteStore,
            TaskDetailCacheEvictionBroadcaster evictionBroadcaster) {
        this.localStore = localStore;
        this.remoteStore = remoteStore;
        this.evictionBroadcaster = evictionBroadcaster;
    }

    /**
     * 读取任务详情快照，未命中时经单飞回源。
     *
     * @param taskId 任务 ID
     * @param loader 回源函数；任务不存在返回 {@code null}
     * @return 快照；任务不存在返回 {@code null}
     */
    public TaskDetailSnapshot get(Long taskId, Supplier<TaskDetailSnapshot> loader) {
        TaskDetailSnapshot local = lookupLocal(taskId);
        if (local != null) {
            return local;
        }

        TaskDetailCacheStore.Lookup remote = lookupRemote(taskId);
        if (remote.hit()) {
            if (remote.missing()) {
                return null;
            }
            storeLocal(taskId, remote.snapshot());
            return remote.snapshot();
        }

        return loadWithSingleFlight(taskId, loader);
    }

    /**
     * 清除两层缓存中该任务的快照与空值标记，并广播给其他实例。
     *
     * <p>本实例清完本地 + Redis 后广播；其他实例收到后只清自己的 Caffeine。
     * 不做这一步时，其他实例的本地层最长会脏 60 秒（{@code CacheConfig.LOCAL_TTL_SECONDS}）。</p>
     *
     * @param taskId 任务 ID
     */
    public void evict(Long taskId) {
        try {
            localStore.invalidate(taskId);
        } catch (RuntimeException e) {
            log.warn("任务详情本地缓存清理失败，taskId={}", taskId, e);
        }
        try {
            remoteStore.invalidate(taskId);
        } catch (RuntimeException e) {
            log.warn("任务详情远端缓存清理失败，taskId={}", taskId, e);
        }
        evictionBroadcaster.broadcast(taskId);
    }

    private TaskDetailSnapshot lookupLocal(Long taskId) {
        try {
            TaskDetailCacheStore.Lookup lookup = localStore.get(taskId);
            return lookup.hit() && !lookup.missing() ? lookup.snapshot() : null;
        } catch (RuntimeException e) {
            log.warn("任务详情本地缓存读取失败，降级，taskId={}", taskId, e);
            return null;
        }
    }

    private TaskDetailCacheStore.Lookup lookupRemote(Long taskId) {
        try {
            return remoteStore.get(taskId);
        } catch (RuntimeException e) {
            log.warn("任务详情远端缓存读取失败，降级回源，taskId={}", taskId, e);
            return TaskDetailCacheStore.Lookup.miss();
        }
    }

    private void storeLocal(Long taskId, TaskDetailSnapshot snapshot) {
        try {
            localStore.put(taskId, snapshot, CacheConfig.LOCAL_TTL_SECONDS);
        } catch (RuntimeException e) {
            log.warn("任务详情本地缓存回填失败，taskId={}", taskId, e);
        }
    }

    private void storeSnapshot(Long taskId, TaskDetailSnapshot snapshot) {
        storeLocal(taskId, snapshot);
        try {
            remoteStore.put(taskId, snapshot,
                    CacheConfig.ttlWithJitter(REMOTE_SNAPSHOT_TTL_SECONDS));
        } catch (RuntimeException e) {
            log.warn("任务详情远端缓存回填失败，taskId={}", taskId, e);
        }
    }

    private void storeMissing(Long taskId) {
        try {
            remoteStore.putMissing(taskId, CacheConfig.ttlWithJitter(REMOTE_MISSING_TTL_SECONDS));
        } catch (RuntimeException e) {
            log.warn("任务详情空值标记回填失败，taskId={}", taskId, e);
        }
    }

    private TaskDetailSnapshot loadWithSingleFlight(Long taskId,
                                                     Supplier<TaskDetailSnapshot> loader) {
        CompletableFuture<TaskDetailSnapshot> mine = new CompletableFuture<>();
        CompletableFuture<TaskDetailSnapshot> existing = inFlight.putIfAbsent(taskId, mine);
        if (existing != null) {
            return awaitLeaderOrLoad(taskId, existing, loader);
        }

        try {
            TaskDetailSnapshot snapshot = loader.get();
            if (snapshot == null) {
                storeMissing(taskId);
            } else {
                storeSnapshot(taskId, snapshot);
            }
            mine.complete(snapshot);
            return snapshot;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(taskId, mine);
        }
    }

    private TaskDetailSnapshot awaitLeaderOrLoad(Long taskId,
                                                 CompletableFuture<TaskDetailSnapshot> future,
                                                 Supplier<TaskDetailSnapshot> loader) {
        try {
            return future.get(SINGLE_FLIGHT_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("任务详情回源等待超时，自行回源，taskId={}", taskId);
            return loader.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return loader.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("task detail load failed", cause);
        }
    }
}
