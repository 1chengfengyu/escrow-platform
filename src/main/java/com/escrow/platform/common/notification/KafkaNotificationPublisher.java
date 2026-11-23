package com.escrow.platform.common.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka 发布器（{@code escrow.notify.mode=kafka}）。
 *
 * <p>用 {@code userId} 作消息键：同一用户的站内信落在同一分区，消费侧天然保序。</p>
 *
 * <p><b>切异步是可靠性降级，必须补降级写库</b>：log 模式的失败窗口只有「写库失败」，
 * 切到 Kafka 后失败窗口扩大到「broker 抖动 / 网络超时 / 主题不存在」。所以发送失败时
 * 直接调 {@link NotificationInboxService#save} 落库，把消息留在用户能查到的地方。
 * 这个降级是安全的：send 失败说明消息未达 broker，直写不会与消费者重复；
 * 「超时但实际到达」的重复由 {@code uk_notification_event} 唯一索引挡掉。</p>
 *
 * <p>降级也失败时只记 error 并放弃——站内信不是资金链路，不接受为了它阻塞或回滚业务事务。</p>
 *
 * @since V5
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "escrow.notify.mode", havingValue = "kafka")
public class KafkaNotificationPublisher implements NotificationPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final NotificationInboxService notificationInboxService;

    @Value("${escrow.notify.topic:escrow-notify}")
    private String topic;

    @Override
    public void publish(NotificationMessage message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("站内信事件序列化失败 eventId=" + message.eventId(), ex);
        }
        try {
            kafkaTemplate.send(topic, String.valueOf(message.userId()), payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("站内信投递失败，降级直写收件箱 eventId={} topic={}",
                                    message.eventId(), topic, ex);
                            fallbackToInbox(message);
                        } else if (log.isDebugEnabled()) {
                            log.debug("站内信已投递 eventId={} offset={}", message.eventId(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (RuntimeException ex) {
            // broker 不可达时 send 会同步抛出（配合 max.block.ms=1000 快速失败），这里同样走降级
            log.error("站内信投递失败，降级直写收件箱 eventId={} topic={}", message.eventId(), topic, ex);
            fallbackToInbox(message);
        }
    }

    private void fallbackToInbox(NotificationMessage message) {
        try {
            notificationInboxService.save(message);
        } catch (RuntimeException e) {
            log.error("站内信降级写库也失败，丢弃 eventId={}", message.eventId(), e);
        }
    }
}
