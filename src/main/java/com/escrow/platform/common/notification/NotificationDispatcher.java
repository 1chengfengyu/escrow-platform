package com.escrow.platform.common.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 站内信事件装配与投递（V5）。
 *
 * <p><b>提交后发布</b>：通知只在业务事务提交后发出；事务回滚时不会出现
 * 「钱没打成功但用户收到打款通知」这种脏消息。无事务（如纯单测）时立即发布。</p>
 *
 * <p><b>不影响业务</b>：发布异常一律只记 warn，绝不向上抛——
 * 站内信是旁路能力，不能因为 broker 不可用把打款事务打回滚。</p>
 *
 * @since V5
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    /** 业务类型：打款。 */
    public static final String BIZ_TYPE_PAYOUT = "PAYOUT";

    private final NotificationPublisher notificationPublisher;

    /**
     * 打款成功通知劳动者。
     *
     * @param userId       劳动者账号 ID
     * @param settlementNo 结算单号（业务单号）
     * @param amount       实际打款金额（分）
     */
    public void payoutSucceeded(Long userId, String settlementNo, long amount) {
        if (userId == null || amount <= 0 || settlementNo == null) {
            return;
        }
        NotificationMessage message = new NotificationMessage(
                UUID.randomUUID().toString(),
                userId,
                BIZ_TYPE_PAYOUT,
                settlementNo,
                "打款已完成",
                "结算单 " + settlementNo + " 的报酬 " + yuan(amount) + " 元已打款，请注意查收。",
                LocalDateTime.now());
        publishAfterCommit(message);
    }

    private void publishAfterCommit(NotificationMessage message) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishQuietly(message);
                }
            });
        } else {
            publishQuietly(message);
        }
    }

    private void publishQuietly(NotificationMessage message) {
        try {
            notificationPublisher.publish(message);
        } catch (RuntimeException ex) {
            log.warn("站内信发布失败，不影响业务 eventId={} bizNo={}",
                    message.eventId(), message.bizNo(), ex);
        }
    }

    /** 分 → 元，仅用于文案；不参与金额运算（金额永远以「分」推进）。 */
    private static String yuan(long amountInFen) {
        return (amountInFen / 100) + "." + String.format("%02d", amountInFen % 100);
    }
}
