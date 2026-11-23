package com.escrow.platform.common.notification;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.escrow.platform.common.infrastructure.mapper.NotificationDeadLetterMapper;
import com.escrow.platform.common.infrastructure.mapper.NotificationInboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 站内信落库与死信落表（V5）。
 *
 * <p><b>消费幂等</b>：靠 {@code uk_notification_event} 唯一索引；重复投递直接命中
 * 唯一键冲突，捕获后按"已存在"处理，绝不先查后写（project_rules §6）。</p>
 *
 * @since V5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationInboxService {

    private final NotificationInboxMapper notificationInboxMapper;
    private final NotificationDeadLetterMapper notificationDeadLetterMapper;

    /**
     * 保存一条站内信；已存在同事件时返回 {@code false}。
     *
     * @param message 通知事件
     * @return 是否新写入
     */
    @Transactional
    public boolean save(NotificationMessage message) {
        NotificationInbox inbox = new NotificationInbox();
        inbox.setId(IdWorker.getId());
        inbox.setEventId(message.eventId());
        inbox.setUserId(message.userId());
        inbox.setBizType(message.bizType());
        inbox.setBizNo(message.bizNo());
        inbox.setTitle(message.title());
        inbox.setContent(message.content());
        inbox.setReadFlag(0);
        inbox.setCreatedAt(message.createdAt() == null ? LocalDateTime.now() : message.createdAt());
        try {
            notificationInboxMapper.insert(inbox);
            return true;
        } catch (DataIntegrityViolationException ex) {
            log.info("站内信重复投递，忽略 eventId={}", message.eventId());
            return false;
        }
    }

    /**
     * 死信落表。
     *
     * @param topic        原主题
     * @param payload      原始消息体
     * @param errorMessage 失败原因
     */
    public void saveDeadLetter(String topic, String payload, String errorMessage) {
        NotificationDeadLetter deadLetter = new NotificationDeadLetter();
        deadLetter.setId(IdWorker.getId());
        deadLetter.setTopic(topic);
        deadLetter.setPayload(payload);
        deadLetter.setErrorMessage(errorMessage == null ? null
                : errorMessage.substring(0, Math.min(500, errorMessage.length())));
        deadLetter.setCreatedAt(LocalDateTime.now());
        notificationDeadLetterMapper.insert(deadLetter);
        log.error("站内信进入死信 topic={} payload={}", topic, payload);
    }
}
