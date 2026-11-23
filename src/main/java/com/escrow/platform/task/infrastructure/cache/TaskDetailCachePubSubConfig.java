package com.escrow.platform.task.infrastructure.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 缓存失效广播的订阅容器（V9）。
 *
 * <p>只订阅 {@link TaskDetailCacheEvictionBroadcaster#CHANNEL} 一个频道。Redis 不可达时
 * 容器自身记 warn 并重试连接，不阻断应用启动；此期间的跨实例脏读由本地 TTL 兜底。</p>
 *
 * @since V9
 */
@Configuration
public class TaskDetailCachePubSubConfig {

    /**
     * 失效广播监听容器。
     *
     * @param connectionFactory Redis 连接工厂（Boot 自动配置）
     * @param listener          失效监听器
     * @return 监听容器
     */
    @Bean
    public RedisMessageListenerContainer taskDetailCacheEvictionContainer(
            RedisConnectionFactory connectionFactory, TaskDetailCacheEvictionListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener,
                new ChannelTopic(TaskDetailCacheEvictionBroadcaster.CHANNEL));
        return container;
    }
}
