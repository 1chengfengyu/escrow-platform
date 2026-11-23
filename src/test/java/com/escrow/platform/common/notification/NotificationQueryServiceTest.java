package com.escrow.platform.common.notification;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.infrastructure.mapper.NotificationInboxMapper;
import com.escrow.platform.common.notification.api.NotificationView;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站内信查询与已读测试（V7，FR-NOTIFY.INBOX.01）。
 *
 * @since V7
 */
@ExtendWith(MockitoExtension.class)
class NotificationQueryServiceTest {

    private static final LoginUser WORKER =
            new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Mock
    private NotificationInboxMapper notificationInboxMapper;

    @InjectMocks
    private NotificationQueryService service;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, NotificationInbox.class);
    }

    /** 未登录：401。 */
    @Test
    void anonymousShouldBeRejected() {
        assertThatThrownBy(() -> service.list(null, null, 1, 20))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    /** readFlag 非 0/1：400。 */
    @Test
    void invalidReadFlagShouldBeRejected() {
        assertThatThrownBy(() -> service.list(WORKER, 2, 1, 20))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        verify(notificationInboxMapper, never()).selectPage(any(), any());
    }

    /** 列表：映射已读标记与分页字段。 */
    @Test
    void listShouldMapReadFlag() {
        NotificationInbox inbox = new NotificationInbox();
        inbox.setId(1L);
        inbox.setUserId(WORKER.userId());
        inbox.setBizType("PAYOUT");
        inbox.setBizNo("P001");
        inbox.setTitle("打款成功");
        inbox.setContent("已到账");
        inbox.setReadFlag(1);
        inbox.setCreatedAt(LocalDateTime.of(2026, 11, 18, 9, 0));
        Page<NotificationInbox> page = new Page<>(1, 20);
        page.setRecords(List.of(inbox));
        page.setTotal(1);
        when(notificationInboxMapper.selectPage(any(), any())).thenReturn(page);

        PageView<NotificationView> result = service.list(WORKER, 1, 1, 20);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.items().get(0).read()).isTrue();
        assertThat(result.items().get(0).bizNo()).isEqualTo("P001");
    }

    /** 标记别人的站内信：404，不泄露存在性。 */
    @Test
    void markOthersMessageShouldBeNotFound() {
        NotificationInbox inbox = new NotificationInbox();
        inbox.setId(1L);
        inbox.setUserId(9999L);
        when(notificationInboxMapper.selectById(1L)).thenReturn(inbox);

        assertThatThrownBy(() -> service.markRead(WORKER, 1L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(notificationInboxMapper, never()).update(any(), any());
    }

    /** 重复标记已读：幂等，不报错。 */
    @Test
    void markReadTwiceShouldBeIdempotent() {
        NotificationInbox inbox = new NotificationInbox();
        inbox.setId(1L);
        inbox.setUserId(WORKER.userId());
        inbox.setReadFlag(1);
        when(notificationInboxMapper.selectById(1L)).thenReturn(inbox);
        when(notificationInboxMapper.update(any(), any())).thenReturn(0);

        service.markRead(WORKER, 1L);

        verify(notificationInboxMapper).update(any(), any());
    }

    /** 全部标记已读：返回受影响行数。 */
    @Test
    void markAllReadShouldReturnUpdatedCount() {
        when(notificationInboxMapper.update(any(), any())).thenReturn(5);

        assertThat(service.markAllRead(WORKER)).isEqualTo(5);
    }

    /** 未读计数为空按 0 处理。 */
    @Test
    void unreadCountShouldTreatNullAsZero() {
        when(notificationInboxMapper.selectCount(any())).thenReturn(null);
        assertThat(service.unreadCount(WORKER)).isZero();
    }
}
