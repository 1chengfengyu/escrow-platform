package com.escrow.platform.common.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 站内信消费者单测（V5）：正常解析落库、解析失败抛给错误处理器、死信落表。
 *
 * @since V5
 */
@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

    private static final NotificationMessage MESSAGE = new NotificationMessage(
            "evt-1", 1001L, "PAYOUT", "ST20261118001", "打款已完成", "已打款 97.00 元",
            LocalDateTime.of(2026, 11, 18, 10, 0));

    @Mock
    private NotificationInboxService notificationInboxService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private NotificationConsumer notificationConsumer;

    @BeforeEach
    void setUp() {
        notificationConsumer = new NotificationConsumer(notificationInboxService, objectMapper);
    }

    /** FR-NOTIFY.CONSUME.01：正常消息解析后交给 inbox 落库。 */
    @Test
    void validMessageShouldBeSaved() throws Exception {
        String payload = objectMapper.writeValueAsString(MESSAGE);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("escrow-notify", 0, 0L, "1001", payload);

        notificationConsumer.onMessage(record);

        ArgumentCaptor<NotificationMessage> captor =
                ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notificationInboxService).save(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo("evt-1");
        assertThat(captor.getValue().userId()).isEqualTo(1001L);
    }

    /** 解析失败必须抛出，交给 DefaultErrorHandler 重试后转 DLT；绝不能静默丢弃。 */
    @Test
    void invalidPayloadShouldThrowForRetry() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("escrow-notify", 0, 0L, "1001", "not-a-json");

        assertThatThrownBy(() -> notificationConsumer.onMessage(record))
                .isInstanceOf(JsonProcessingException.class);
        verify(notificationInboxService, never()).save(any());
    }

    /** 死信只落表，不再调用正常落库。 */
    @Test
    void deadLetterShouldBePersistedWithReason() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("escrow-notify.DLT", 0, 0L, "1001", "not-a-json");
        record.headers().add("kafka_dlt-exception-message",
                "JSON parse error".getBytes(StandardCharsets.UTF_8));

        notificationConsumer.onDeadLetter(record);

        verify(notificationInboxService).saveDeadLetter("escrow-notify.DLT", "not-a-json",
                "JSON parse error");
        verify(notificationInboxService, never()).save(any());
    }
}
