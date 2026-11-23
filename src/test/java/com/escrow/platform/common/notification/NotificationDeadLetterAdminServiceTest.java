package com.escrow.platform.common.notification;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.infrastructure.mapper.NotificationDeadLetterMapper;
import com.escrow.platform.common.notification.api.DeadLetterView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 死信查询与重放测试（V8，FR-MSG.DLT.01）。
 *
 * <p>守三条：非管理员一律 403；坏原文在入口被拒且不投递；合法原文重放要留审计。</p>
 *
 * @since V8
 */
@ExtendWith(MockitoExtension.class)
class NotificationDeadLetterAdminServiceTest {

    private static final long DEAD_LETTER_ID = 31L;
    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);
    private static final LoginUser WORKER =
            new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    private static final String VALID_PAYLOAD =
            "{\"eventId\":\"evt-1\",\"userId\":1001,\"bizType\":\"PAYOUT\",\"bizNo\":\"S001\","
                    + "\"title\":\"打款完成\",\"content\":\"已到账\",\"createdAt\":\"2026-11-20T10:00:00\"}";

    @Mock
    private NotificationDeadLetterMapper notificationDeadLetterMapper;
    @Mock
    private NotificationPublisher notificationPublisher;
    @Mock
    private AuditRecorder auditRecorder;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private NotificationDeadLetterAdminService service;

    @BeforeAll
    static void initTableInfo() {
        String namespace = "com.escrow.platform.common.infrastructure.mapper.NotificationDeadLetterMapper";
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NotificationDeadLetter.class);
    }

    private NotificationDeadLetter deadLetter(String payload) {
        NotificationDeadLetter row = new NotificationDeadLetter();
        row.setId(DEAD_LETTER_ID);
        row.setTopic("escrow-notify.DLT");
        row.setPayload(payload);
        row.setErrorMessage("消费失败");
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    /** 非管理员查询：403，且不碰数据库。 */
    @Test
    void listShouldRejectNonAdmin() {
        assertThatThrownBy(() -> service.list(WORKER, 20))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(notificationDeadLetterMapper, never()).selectPage(any(), any());
    }

    /** 列表：limit 超过 100 时按 100 取，payload 截断到 500 字符。 */
    @Test
    void listShouldClampLimitAndTruncatePayload() {
        NotificationDeadLetter row = deadLetter("x".repeat(600));
        Page<NotificationDeadLetter> page = new Page<>(1, 100, false);
        page.setRecords(List.of(row));
        when(notificationDeadLetterMapper.selectPage(any(), any())).thenReturn(page);

        List<DeadLetterView> views = service.list(ADMIN, 500);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Page<NotificationDeadLetter>> captor = ArgumentCaptor.forClass(Page.class);
        verify(notificationDeadLetterMapper).selectPage(captor.capture(), any());
        assertThat(captor.getValue().getSize()).isEqualTo(100L);
        assertThat(views).hasSize(1);
        assertThat(views.get(0).payload()).hasSize(500);
        assertThat(views.get(0).topic()).isEqualTo("escrow-notify.DLT");
    }

    /** 重放成功：按原 eventId 投递一次，计数 +1，并写审计。 */
    @Test
    void replayShouldPublishAndAudit() {
        NotificationDeadLetter row = deadLetter(VALID_PAYLOAD);
        row.setReplayCount(2);
        when(notificationDeadLetterMapper.selectById(DEAD_LETTER_ID)).thenReturn(row);
        when(notificationDeadLetterMapper.update(any(), any())).thenReturn(1);

        DeadLetterView view = service.replay(ADMIN, DEAD_LETTER_ID);

        ArgumentCaptor<NotificationMessage> message = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notificationPublisher).publish(message.capture());
        assertThat(message.getValue().eventId()).isEqualTo("evt-1");
        assertThat(message.getValue().userId()).isEqualTo(1001L);
        verify(notificationDeadLetterMapper).update(eq(null), any());
        verify(auditRecorder).record(eq("NOTIFICATION_DLT"), eq(String.valueOf(DEAD_LETTER_ID)),
                eq("REPLAY"), eq(null), eq(null), contains("evt-1"));
        assertThat(view.id()).isEqualTo(DEAD_LETTER_ID);
        assertThat(view.replayCount()).isEqualTo(3);
        assertThat(view.lastReplayedAt()).isNotNull();
    }

    /** 原文不是 JSON：400，且绝不投递（避免把坏消息重新灌进 topic）。 */
    @Test
    void replayShouldRejectIllegalPayload() {
        when(notificationDeadLetterMapper.selectById(DEAD_LETTER_ID)).thenReturn(deadLetter("not-json"));

        assertThatThrownBy(() -> service.replay(ADMIN, DEAD_LETTER_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(notificationPublisher, never()).publish(any());
        verify(auditRecorder, never()).record(any(), any(), any(), any(), any(), any());
    }

    /** JSON 合法但缺 eventId：同样拒绝。 */
    @Test
    void replayShouldRejectMessageWithoutEventId() {
        when(notificationDeadLetterMapper.selectById(DEAD_LETTER_ID))
                .thenReturn(deadLetter("{\"userId\":1001,\"title\":\"t\"}"));

        assertThatThrownBy(() -> service.replay(ADMIN, DEAD_LETTER_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    /** 死信不存在：404。 */
    @Test
    void replayShouldReturnNotFound() {
        when(notificationDeadLetterMapper.selectById(DEAD_LETTER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.replay(ADMIN, DEAD_LETTER_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
