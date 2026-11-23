package com.escrow.platform.task.infrastructure.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 用 Redis Pub/Sub 广播缓存失效（V9）。
 *
 * <p>只用 {@link StringRedisTemplate}，不引 Redisson——与 {@code RateLimiter} 的既有约定一致。
 * 发后即忘：publish 失败只记 warn，不阻断业务；订阅侧断线期间的脏数据靠 60 秒本地 TTL 兜底，
 * 所以这里不承诺「强一致失效」。</p>
 *
 * @since V9
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisTaskDetailCacheEvictionBroadcaster implements TaskDetailCacheEvictionBroadcaster {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void broadcast(Long taskId) {
        if (taskId == null) {
            return;
        }
        try {
            stringRedisTemplate.convertAndSend(CHANNEL, String.valueOf(taskId));
        } catch (RuntimeException e) {
            log.warn("任务详情缓存失效广播失败，其他实例最长脏 60 秒，taskId={}", taskId, e);
        }
    }
}
