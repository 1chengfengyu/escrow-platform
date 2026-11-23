package com.escrow.platform.common.notification;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * 消费端错误处理（V5）：重试两次后转 DLT，绝不无限重试。
 *
 * @since V5
 */
@Configuration
@ConditionalOnProperty(name = "escrow.notify.mode", havingValue = "kafka")
public class NotificationKafkaConfig {

    /**
     * 默认错误处理器：间隔 500ms、重试 2 次，之后投递到 {@code <topic>.DLT}。
     *
     * <p><b>必须显式指定目标主题</b>：Spring Kafka 3.x 的 {@code DeadLetterPublishingRecoverer}
     * 默认后缀是 {@code -dlt}，而 {@link NotificationTopicConfig} 与 {@code @KafkaListener}
     * 用的都是 {@code .DLT}。不改的话投递会打到不存在的 {@code escrow-notify-dlt}，
     * 死信永远落不了表，且分区被毒消息卡住、后续消息全部积压。</p>
     *
     * @param kafkaTemplate Boot 自动装配的模板
     * @return 错误处理器
     */
    @Bean
    public DefaultErrorHandler notificationErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        return new DefaultErrorHandler(deadLetterRecoverer(kafkaTemplate), new FixedBackOff(500L, 2L));
    }

    /** 死信投递器：目标主题必须是 {@code <topic>.DLT}，与监听器和主题声明保持一致。 */
    DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaTemplate<String, String> kafkaTemplate) {
        return new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));
    }

    /**
     * 发送端快速失败：broker 不可用时 {@code KafkaProducer.send} 会阻塞到元数据超时，
     * 默认 {@code max.block.ms=60s}，会把业务请求线程一起拖死，降级写库也就无从发生。
     * 压到 1 秒，让发送端尽快抛错，由 {@link KafkaNotificationPublisher} 接住并直写收件箱。
     *
     * @return 生产者工厂定制器
     */
    @Bean
    public DefaultKafkaProducerFactoryCustomizer notificationProducerFactoryCustomizer() {
        return factory -> factory.updateConfigs(Map.of(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000));
    }
}
