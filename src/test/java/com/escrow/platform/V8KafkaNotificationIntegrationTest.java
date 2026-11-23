package com.escrow.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.common.infrastructure.mapper.NotificationInboxMapper;
import com.escrow.platform.common.notification.KafkaNotificationPublisher;
import com.escrow.platform.common.notification.NotificationInbox;
import com.escrow.platform.common.notification.NotificationMessage;
import com.escrow.platform.common.notification.NotificationPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka 端到端：发布 → broker → 消费 → 落 {@code notification}（V8）。
 *
 * <p>默认 {@code mvn test} 不跑（{@code ESCROW_KAFKA_IT} 未设置）。前置条件：
 * {@code docker compose --profile core --profile kafka up -d} 已启动 broker，
 * 且测试库可达。</p>
 *
 * <p>第一条用例先直接 {@code send(...).get()} 探活：broker 不可达时这里就失败，
 * 不会因为降级写库把「Kafka 根本没通」误判成通过。</p>
 *
 * @since V8
 */
@SpringBootTest
@ActiveProfiles({"test", "kafka"})
@EnabledIfEnvironmentVariable(named = "ESCROW_KAFKA_IT", matches = "true")
class V8KafkaNotificationIntegrationTest {

    private static final String TOPIC = "escrow-notify";
    private static final long USER_ID = 9101L;

    @Autowired
    private NotificationPublisher notificationPublisher;

    @Autowired
    private NotificationInboxMapper notificationInboxMapper;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    /** kafka profile 下装配的必须是 Kafka 发布器，而不是 log 模式那个。 */
    @Test
    void kafkaProfileShouldWireKafkaPublisher() {
        assertThat(notificationPublisher).isInstanceOf(KafkaNotificationPublisher.class);
    }

    /** 发布 → 消费 → 落库：收件箱最终出现该事件。 */
    @Test
    void publishedMessageShouldBeConsumedAndPersisted() throws Exception {
        assertBrokerReachable();
        String eventId = "it-v8-" + UUID.randomUUID();

        notificationPublisher.publish(message(eventId));

        assertThat(awaitInboxRows(eventId, 1)).isEqualTo(1);
    }

    /** 重复投递同一 eventId：唯一索引挡掉，收件箱仍只有 1 行。 */
    @Test
    void duplicateEventIdShouldPersistOnce() throws Exception {
        assertBrokerReachable();
        String eventId = "it-v8-dup-" + UUID.randomUUID();

        notificationPublisher.publish(message(eventId));
        assertThat(awaitInboxRows(eventId, 1)).isEqualTo(1);

        notificationPublisher.publish(message(eventId));
        Thread.sleep(2_000L);

        assertThat(countInboxRows(eventId)).isEqualTo(1);
    }

    private void assertBrokerReachable() throws Exception {
        kafkaTemplate.send(TOPIC, "probe", "{\"probe\":true}").get(10, TimeUnit.SECONDS);
    }

    private long awaitInboxRows(String eventId, long expected) throws InterruptedException {
        long rows = 0L;
        for (int i = 0; i < 30; i++) {
            rows = countInboxRows(eventId);
            if (rows >= expected) {
                return rows;
            }
            Thread.sleep(500L);
        }
        return rows;
    }

    private long countInboxRows(String eventId) {
        return notificationInboxMapper.selectCount(new LambdaQueryWrapper<NotificationInbox>()
                .eq(NotificationInbox::getEventId, eventId));
    }

    private static NotificationMessage message(String eventId) {
        return new NotificationMessage(eventId, USER_ID, "PAYOUT", "IT-V8",
                "打款已完成", "端到端测试", LocalDateTime.now());
    }
}
