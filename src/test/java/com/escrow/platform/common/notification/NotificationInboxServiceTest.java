package com.escrow.platform.common.notification;

import com.escrow.platform.common.infrastructure.mapper.NotificationDeadLetterMapper;
import com.escrow.platform.common.infrastructure.mapper.NotificationInboxMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站内信落库单测（V5）：消费幂等与死信落表。
 *
 * @since V5
 */
@ExtendWith(MockitoExtension.class)
class NotificationInboxServiceTest {

    private static final NotificationMessage MESSAGE = new NotificationMessage(
            "evt-1", 1001L, "PAYOUT", "ST20261118001", "打款已完成", "已打款 97.00 元",
            LocalDateTime.of(2026, 11, 18, 10, 0));

    @Mock
    private NotificationInboxMapper notificationInboxMapper;

    @Mock
    private NotificationDeadLetterMapper notificationDeadLetterMapper;

    @InjectMocks
    private NotificationInboxService notificationInboxService;

    /** FR-NOTIFY.INBOX.01：首次投递写入站内信，字段与事件一致。 */
    @Test
    void firstDeliveryShouldInsertInbox() {
        boolean created = notificationInboxService.save(MESSAGE);

        ArgumentCaptor<NotificationInbox> captor =
                ArgumentCaptor.forClass(NotificationInbox.class);
        verify(notificationInboxMapper).insert(captor.capture());
        NotificationInbox inbox = captor.getValue();
        assertThat(created).isTrue();
        assertThat(inbox.getEventId()).isEqualTo("evt-1");
        assertThat(inbox.getUserId()).isEqualTo(1001L);
        assertThat(inbox.getBizNo()).isEqualTo("ST20261118001");
        assertThat(inbox.getReadFlag()).isZero();
        assertThat(inbox.getCreatedAt()).isEqualTo(MESSAGE.createdAt());
        assertThat(inbox.getId()).isNotNull();
    }

    /** FR-NOTIFY.INBOX.01：唯一键冲突视为「已存在」，不抛给 Kafka 重试。 */
    @Test
    void duplicateDeliveryShouldBeIgnored() {
        when(notificationInboxMapper.insert(any(NotificationInbox.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for uk_notification_event"));

        assertThat(notificationInboxService.save(MESSAGE)).isFalse();
    }

    /** 死信落表，失败原因按 500 字符截断。 */
    @Test
    void deadLetterShouldBePersisted() {
        String longError = "x".repeat(600);

        notificationInboxService.saveDeadLetter("escrow-notify.DLT", "not-json", longError);

        ArgumentCaptor<NotificationDeadLetter> captor =
                ArgumentCaptor.forClass(NotificationDeadLetter.class);
        verify(notificationDeadLetterMapper).insert(captor.capture());
        NotificationDeadLetter deadLetter = captor.getValue();
        assertThat(deadLetter.getTopic()).isEqualTo("escrow-notify.DLT");
        assertThat(deadLetter.getPayload()).isEqualTo("not-json");
        assertThat(deadLetter.getErrorMessage()).hasSize(500);
        assertThat(deadLetter.getCreatedAt()).isNotNull();
    }
}
