package com.escrow.platform.common.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.infrastructure.mapper.NotificationDeadLetterMapper;
import com.escrow.platform.common.notification.api.DeadLetterView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 死信查询与重放（V8，FR-MSG.DLT.01）。
 *
 * <p>死信是「已放弃重试」的消息，重放是人工兜底而不是自动恢复：一次只放一条，
 * 且必须先能解析成 {@link NotificationMessage}——语义坏掉的消息重放多少次都不会成功，
 * 直接在入口拒掉，不去污染通知表。</p>
 *
 * <p>重放走 {@link NotificationPublisher} 而不是直连 Kafka：broker 不可用时
 * 发布器自己会降级直写收件箱，重放语义不变。本方法**不加事务**，
 * 避免把远程调用包进数据库事务。</p>
 *
 * @since V8
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDeadLetterAdminService {

    private static final int MAX_LIMIT = 100;
    private static final int MAX_PAYLOAD_IN_VIEW = 500;
    private static final String ADMIN_ONLY_MESSAGE = "仅管理员可操作死信";
    private static final String NOT_FOUND_MESSAGE = "死信不存在";
    private static final String ILLEGAL_PAYLOAD_MESSAGE = "死信原文不是合法的通知消息，无法重放";
    private static final String AUDIT_BIZ_TYPE = "NOTIFICATION_DLT";

    private final NotificationDeadLetterMapper notificationDeadLetterMapper;
    private final NotificationPublisher notificationPublisher;
    private final ObjectMapper objectMapper;
    private final AuditRecorder auditRecorder;

    /**
     * 按落表时间倒序查询死信（FR-MSG.DLT.01）。
     *
     * @param current 会话主体，必须是 ADMIN
     * @param limit   条数，1~{@value #MAX_LIMIT}
     * @return 死信视图列表
     * @throws ApiException 403 非管理员
     */
    public List<DeadLetterView> list(LoginUser current, int limit) {
        requireAdmin(current);
        int size = Math.max(1, Math.min(MAX_LIMIT, limit));
        Page<NotificationDeadLetter> page = notificationDeadLetterMapper.selectPage(
                new Page<>(1, size, false),
                new LambdaQueryWrapper<NotificationDeadLetter>()
                        .orderByDesc(NotificationDeadLetter::getCreatedAt)
                        .orderByDesc(NotificationDeadLetter::getId));
        return page.getRecords().stream().map(NotificationDeadLetterAdminService::toView).toList();
    }

    /**
     * 重放一条死信（FR-MSG.DLT.01）。
     *
     * <p>重复重放由消费侧 {@code eventId} 唯一键兜底：第二次不会新增站内信。</p>
     *
     * @param current 会话主体，必须是 ADMIN
     * @param id      死信 ID
     * @return 被重放的死信视图
     * @throws ApiException 403 非管理员 / 404 死信不存在 / 400 原文不可解析
     */
    public DeadLetterView replay(LoginUser current, Long id) {
        requireAdmin(current);
        NotificationDeadLetter deadLetter = id == null ? null : notificationDeadLetterMapper.selectById(id);
        if (deadLetter == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        NotificationMessage message = parse(deadLetter.getPayload());
        notificationPublisher.publish(message);
        LocalDateTime replayedAt = LocalDateTime.now();
        // 原子自增：并发重放同一条时计数不会丢
        notificationDeadLetterMapper.update(null, new LambdaUpdateWrapper<NotificationDeadLetter>()
                .eq(NotificationDeadLetter::getId, id)
                .setSql("replay_count = replay_count + 1")
                .set(NotificationDeadLetter::getLastReplayedAt, replayedAt));
        auditRecorder.record(AUDIT_BIZ_TYPE, String.valueOf(id), "REPLAY", null, null,
                "topic=" + deadLetter.getTopic() + " eventId=" + message.eventId());
        log.info("死信重放 id={} topic={} eventId={}", id, deadLetter.getTopic(), message.eventId());
        int replayCount = deadLetter.getReplayCount() == null ? 0 : deadLetter.getReplayCount();
        return toView(deadLetter, replayCount + 1, replayedAt);
    }

    private NotificationMessage parse(String payload) {
        NotificationMessage message;
        try {
            message = objectMapper.readValue(payload, NotificationMessage.class);
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ILLEGAL_PAYLOAD_MESSAGE);
        }
        if (message == null || message.eventId() == null || message.eventId().isBlank()
                || message.userId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ILLEGAL_PAYLOAD_MESSAGE);
        }
        return message;
    }

    private static DeadLetterView toView(NotificationDeadLetter deadLetter) {
        int replayCount = deadLetter.getReplayCount() == null ? 0 : deadLetter.getReplayCount();
        return toView(deadLetter, replayCount, deadLetter.getLastReplayedAt());
    }

    private static DeadLetterView toView(NotificationDeadLetter deadLetter, int replayCount,
                                         LocalDateTime lastReplayedAt) {
        String payload = deadLetter.getPayload();
        if (payload != null && payload.length() > MAX_PAYLOAD_IN_VIEW) {
            payload = payload.substring(0, MAX_PAYLOAD_IN_VIEW);
        }
        return new DeadLetterView(deadLetter.getId(), deadLetter.getTopic(), payload,
                deadLetter.getErrorMessage(), replayCount, lastReplayedAt, deadLetter.getCreatedAt());
    }

    private static void requireAdmin(LoginUser current) {
        if (current == null || current.currentIdentity() != IdentityType.ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN, ADMIN_ONLY_MESSAGE);
        }
    }
}
