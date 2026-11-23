package com.escrow.platform.common.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * log 模式发布器测试（V7：收件箱必须有真实数据可查）。
 *
 * @since V7
 */
@ExtendWith(MockitoExtension.class)
class LoggingNotificationPublisherTest {

    @Mock
    private NotificationInboxService notificationInboxService;

    @InjectMocks
    private LoggingNotificationPublisher publisher;

    /** log 模式发布即落库，保证站内信可查询。 */
    @Test
    void publishShouldPersistInbox() {
        NotificationMessage message = new NotificationMessage("evt-1", 1001L, "PAYOUT",
                "S001", "打款已完成", "已到账", LocalDateTime.now());
        when(notificationInboxService.save(message)).thenReturn(true);

        publisher.publish(message);

        verify(notificationInboxService).save(message);
    }
}
