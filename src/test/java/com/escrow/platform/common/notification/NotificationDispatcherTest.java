package com.escrow.platform.common.notification;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 站内信分发单测（V5，FR-PAYOUT.NOTIFY.01）。
 *
 * <p>核心断言是「提交后才发」：事务未提交时发布器一次都不应被调用。</p>
 *
 * @since V5
 */
@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    @Mock
    private NotificationPublisher notificationPublisher;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** FR-PAYOUT.NOTIFY.01：事务内只登记同步器，提交前不发布。 */
    @Test
    void payoutSucceededShouldPublishOnlyAfterCommit() {
        NotificationDispatcher dispatcher = new NotificationDispatcher(notificationPublisher);
        TransactionSynchronizationManager.initSynchronization();

        dispatcher.payoutSucceeded(1001L, "ST20261118001", 9_700L);

        verifyNoInteractions(notificationPublisher);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        ArgumentCaptor<NotificationMessage> captor =
                ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notificationPublisher).publish(captor.capture());
        NotificationMessage message = captor.getValue();
        assertThat(message.userId()).isEqualTo(1001L);
        assertThat(message.bizType()).isEqualTo(NotificationDispatcher.BIZ_TYPE_PAYOUT);
        assertThat(message.bizNo()).isEqualTo("ST20261118001");
        assertThat(message.content()).contains("97.00");
        assertThat(message.eventId()).isNotBlank();
    }

    /** 无事务（纯单测 / 普通调用）时立即发布。 */
    @Test
    void payoutSucceededShouldPublishImmediatelyWithoutTransaction() {
        NotificationDispatcher dispatcher = new NotificationDispatcher(notificationPublisher);

        dispatcher.payoutSucceeded(1001L, "ST20261118002", 30_00L);

        verify(notificationPublisher).publish(any(NotificationMessage.class));
    }

    /** 发布异常只记 warn，不得影响打款事务。 */
    @Test
    void publishFailureShouldNotPropagate() {
        NotificationDispatcher dispatcher = new NotificationDispatcher(notificationPublisher);
        doThrow(new IllegalStateException("broker down"))
                .when(notificationPublisher).publish(any(NotificationMessage.class));

        assertThatCode(() -> dispatcher.payoutSucceeded(1001L, "ST20261118003", 100L))
                .doesNotThrowAnyException();
    }

    /** 零金额 / 空接收人不发通知：避免把「零扣款」写成「已打款 0 元」。 */
    @Test
    void nonPositiveAmountShouldNotPublish() {
        NotificationDispatcher dispatcher = new NotificationDispatcher(notificationPublisher);

        dispatcher.payoutSucceeded(1001L, "ST20261118004", 0L);
        dispatcher.payoutSucceeded(null, "ST20261118005", 100L);

        verify(notificationPublisher, never()).publish(any(NotificationMessage.class));
    }
}
