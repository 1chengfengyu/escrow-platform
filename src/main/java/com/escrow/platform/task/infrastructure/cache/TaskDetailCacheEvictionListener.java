package com.escrow.platform.task.infrastructure.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 订阅缓存失效广播，清理本实例的本地 Caffeine 层（V9）。
 *
 * <p>只清本地层：Redis 层由发起方 {@link TaskDetailCache#evict(Long)} 已经删过，
 * 这里再删一次是多余的网络往返；重复收到广播时做一次无效 invalidate 也无副作用。</p>
 *
 * @since V9
 */
@Slf4j
@Component
public class TaskDetailCacheEvictionListener implements MessageListener {

    private final TaskDetailCacheStore localStore;

    public TaskDetailCacheEvictionListener(
            @Qualifier("caffeineTaskDetailCacheStore") TaskDetailCacheStore localStore) {
        this.localStore = localStore;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8).trim();
        try {
            Long taskId = Long.valueOf(payload);
            localStore.invalidate(taskId);
        } catch (RuntimeException e) {
            log.warn("任务详情缓存失效广播处理失败，忽略 payload={}", payload, e);
        }
    }
}
