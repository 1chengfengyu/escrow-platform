package com.escrow.platform.common.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 站内信消费者 + 死信消费者（V5，{@code escrow.notify.mode=kafka}）。
 *
 * <p>正常消息解析失败会抛异常，由 {@code DefaultErrorHandler + DeadLetterPublishingRecoverer}
 * 转入 {@code <topic>.DLT}；死信消费者只负责落表，不再重试。</p>
 *
 * @since V5
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "escrow.notify.mode", havingValue = "kafka")
public class NotificationConsumer {

    private static final String DLT_EXCEPTION_HEADER = "kafka_dlt-exception-message";

    private final NotificationInboxService notificationInboxService;
    private final ObjectMapper objectMapper;

    /**
     * 消费站内信。
     *
     * @param record Kafka 记录
     * @throws Exception 解析失败时抛出，交给错误处理器转死信
     */
    @KafkaListener(topics = "${escrow.notify.topic:escrow-notify}",
            groupId = "${escrow.notify.group:escrow-platform}",
            autoStartup = "${escrow.notify.auto-startup:true}")
    public void onMessage(ConsumerRecord<String, String> record) throws Exception {
        NotificationMessage message = objectMapper.readValue(record.value(), NotificationMessage.class);
        notificationInboxService.save(message);
    }

    /**
     * 消费死信并落表。
     *
     * @param record DLT 记录
     */
    @KafkaListener(topics = "${escrow.notify.topic:escrow-notify}.DLT",
            groupId = "${escrow.notify.group:escrow-platform}-dlt",
            autoStartup = "${escrow.notify.auto-startup:true}")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(DLT_EXCEPTION_HEADER);
        String error = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        notificationInboxService.saveDeadLetter(record.topic(), record.value(), error);
    }
}
