package com.escrow.platform.common.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Kafka 通知配置回归测试（V8）：守住两个只有真 Broker 才会暴露的坑。
 *
 * <p>这两个 bug 在 2026-11-20 的真机验证中被抓到：死信被打到不存在的 {@code -dlt} 主题，
 * 毒消息卡死分区；broker 停机时 {@code send} 阻塞 60 秒，降级写库从未发生。</p>
 *
 * @since V8
 */
@ExtendWith(MockitoExtension.class)
class NotificationKafkaConfigTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    /** 死信目标必须是声明的 {@code escrow-notify.DLT}，而不是框架默认的 {@code -dlt} 后缀。 */
    @Test
    void deadLetterShouldGoToDeclaredDotDltTopic() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("escrow-notify", 0, 12L, "1001", "not-json");

        new NotificationKafkaConfig().deadLetterRecoverer(kafkaTemplate)
                .accept(record, new IllegalStateException("消费失败"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        org.mockito.Mockito.verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("escrow-notify.DLT");
    }

    /** 发送端必须快速失败：60 秒的默认阻塞会把业务请求线程一起拖死，降级就永远轮不到。 */
    @Test
    void producerShouldFailFastWhenBrokerUnavailable() {
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(Map.of());

        new NotificationKafkaConfig().notificationProducerFactoryCustomizer().customize(factory);

        assertThat(factory.getConfigurationProperties())
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
    }
}
