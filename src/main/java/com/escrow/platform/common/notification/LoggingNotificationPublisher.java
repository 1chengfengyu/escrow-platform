package com.escrow.platform.common.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认发布器：打日志并直接写收件箱（{@code escrow.notify.mode=log} 或缺省）。
 *
 * <p>没有 Kafka 也能完整启动与演示；日志中的 {@code eventId} 与 Kafka 模式一致，
 * 排查时可以直接对照。V7 起站内信要能被用户查询，log 模式必须同步落库，
 * 否则「有通知但收件箱永远为空」——Kafka 模式由消费者落库，两者互斥不重复。</p>
 *
 * @since V5
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "escrow.notify.mode", havingValue = "log", matchIfMissing = true)
public class LoggingNotificationPublisher implements NotificationPublisher {

    private final NotificationInboxService notificationInboxService;

    @Override
    public void publish(NotificationMessage message) {
        log.info("站内信[log模式] eventId={} userId={} bizType={} bizNo={} title={}",
                message.eventId(), message.userId(), message.bizType(), message.bizNo(),
                message.title());
        notificationInboxService.save(message);
    }
}
