package com.escrow.platform.task.infrastructure.cache;

import com.escrow.platform.common.config.CacheConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 任务详情缓存的 Redis 层（v2-c4，docs/decisions.md #49）。
 *
 * <p>键固定为「任务详情前缀 + taskId」，<b>键里不出现观察者身份</b>——快照本身与
 * 观察者无关，放进 userId / 角色会让同一任务分裂成多份副本，也会泄露给不该看到的人。</p>
 *
 * <p>空值标记为字符串 {@code __NULL__}：DB 查不到时只写这一层（60 秒 + 抖动），
 * 且能被 {@link #invalidate(Long)} 一并清掉，写入后下一次读会重新回源。</p>
 *
 * <p>任何 Redis 异常（连不上 / 超时 / 序列化失败）都在本层 catch 成 warn 日志并降级：
 * 读返回未命中、写与删变成无操作。缓存故障不能放大成接口 5xx（卡片 §4.5）。</p>
 *
 * @since V2
 */
@Slf4j
@Component("redisTaskDetailCacheStore")
public class RedisTaskDetailCacheStore implements TaskDetailCacheStore {

    private static final String NULL_MARKER = "__NULL__";

    private final StringRedisTemplate redis;
    private final CacheConfig.JsonCodec codec;

    public RedisTaskDetailCacheStore(StringRedisTemplate redis, CacheConfig.JsonCodec codec) {
        this.redis = redis;
        this.codec = codec;
    }

    private static String key(Long taskId) {
        return "task:detail:" + taskId;
    }

    @Override
    public Lookup get(Long taskId) {
        String redisKey = key(taskId);
        try {
            String raw = redis.opsForValue().get(redisKey);
            if (raw == null) {
                return Lookup.miss();
            }
            if (NULL_MARKER.equals(raw)) {
                return Lookup.nullMarker();
            }
            return Lookup.present(codec.fromJson(raw, TaskDetailSnapshot.class));
        } catch (RuntimeException e) {
            log.warn("任务详情缓存读取失败，降级回源，taskId={}", taskId, e);
            return Lookup.miss();
        }
    }

    @Override
    public void put(Long taskId, TaskDetailSnapshot snapshot, long ttlSeconds) {
        String redisKey = key(taskId);
        try {
            redis.opsForValue().set(redisKey, codec.toJson(snapshot), ttlSeconds, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            log.warn("任务详情缓存写入失败，忽略本次写，taskId={}", taskId, e);
        }
    }

    @Override
    public void putMissing(Long taskId, long ttlSeconds) {
        String redisKey = key(taskId);
        try {
            redis.opsForValue().set(redisKey, NULL_MARKER, ttlSeconds, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            log.warn("任务详情空值标记写入失败，忽略本次写，taskId={}", taskId, e);
        }
    }

    @Override
    public void invalidate(Long taskId) {
        String redisKey = key(taskId);
        try {
            redis.delete(redisKey);
        } catch (RuntimeException e) {
            log.warn("任务详情缓存清理失败，忽略本次清理，taskId={}", taskId, e);
        }
    }
}
