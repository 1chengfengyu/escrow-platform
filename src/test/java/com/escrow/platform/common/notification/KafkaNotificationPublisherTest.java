package com.escrow.platform.common.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kafka 发布器降级测试（V8）：切异步是可靠性降级，发送失败必须回落写库。
 *
 * <p>覆盖三条：发送失败 → 直写收件箱；降级写库也失败 → 只记日志不外抛；
 * 发送成功 → 不重复写库（消费侧落库）。</p>
 *
 * @since V8
 */
@ExtendWith(MockitoExtension.class)
class KafkaNotificationPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private NotificationInboxService notificationInboxService;

    private KafkaNotificationPublisher publisher;

    private NotificationMessage message;

    @BeforeEach
    void setUp() {
        publisher = new KafkaNotificationPublisher(kafkaTemplate, new ObjectMapper().findAndRegisterModules(),
                notificationInboxService);
        ReflectionTestUtils.setField(publisher, "topic", "escrow-notify");
        message = new NotificationMessage("evt-v8", 1001L, "PAYOUT", "S001",
                "打款已完成", "已到账", LocalDateTime.now());
    }

    /** 发送失败：消息没进 broker，直写收件箱，保证用户仍能查到。 */
    @Test
    void sendFailureShouldFallbackToInbox() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new CompletionException(
                        new IllegalStateException("broker down"))));

        publisher.publish(message);

        verify(notificationInboxService).save(message);
    }

    /** 同步抛出（broker 元数据不可用且 max.block.ms 已压到 1 秒）：同样必须降级直写。 */
    @Test
    void synchronousSendFailureShouldFallbackToInbox() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new org.apache.kafka.common.errors.TimeoutException("metadata timeout"));

        assertThatCode(() -> publisher.publish(message)).doesNotThrowAnyException();
        verify(notificationInboxService).save(message);
    }

    /** 降级写库也失败：只记日志，绝不向上抛（发布在事务提交后回调里执行）。 */
    @Test
    void fallbackFailureShouldNotPropagate() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new CompletionException(
                        new IllegalStateException("broker down"))));
        when(notificationInboxService.save(message)).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> publisher.publish(message)).doesNotThrowAnyException();
    }

    /** 发送成功：由消费者落库，发布侧不能重复写。 */
    @Test
    void sendSuccessShouldNotWriteInbox() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        publisher.publish(message);

        verify(notificationInboxService, never()).save(message);
    }
}
