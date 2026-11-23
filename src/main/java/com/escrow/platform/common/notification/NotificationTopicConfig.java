package com.escrow.platform.common.notification;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 站内信主题声明（V8，仅 {@code escrow.notify.mode=kafka} 时生效）。
 *
 * <p>显式声明主题而不是依赖 broker 自动创建：compose 里 {@code KAFKA_AUTO_CREATE_TOPICS_ENABLE}
 * 已关闭，主题必须由应用建，否则第一次投递会因主题不存在失败——而失败分支只会走降级写库，
 * 「异步」就退化成「同步 + 一条错误日志」，问题还会被降级掩盖。</p>
 *
 * <p>3 分区对齐 {@code V9} 的三实例并行消费；副本数 1 是因为本地只有单 broker。</p>
 *
 * @since V8
 */
@Configuration
@ConditionalOnProperty(name = "escrow.notify.mode", havingValue = "kafka")
public class NotificationTopicConfig {

    /** 与 {@code KafkaNotificationPublisher} 的默认主题保持一致。 */
    static final String NOTIFY_TOPIC = "escrow-notify";

    /** {@code DeadLetterPublishingRecoverer} 的默认后缀是 {@code .DLT}。 */
    static final String NOTIFY_DLT_TOPIC = NOTIFY_TOPIC + ".DLT";

    /**
     * 站内信主主题。
     *
     * @return 主题声明
     */
    @Bean
    public NewTopic notificationTopic() {
        return TopicBuilder.name(NOTIFY_TOPIC).partitions(3).replicas(1).build();
    }

    /**
     * 站内信死信主题。
     *
     * @return 主题声明
     */
    @Bean
    public NewTopic notificationDeadLetterTopic() {
        return TopicBuilder.name(NOTIFY_DLT_TOPIC).partitions(3).replicas(1).build();
    }
}
