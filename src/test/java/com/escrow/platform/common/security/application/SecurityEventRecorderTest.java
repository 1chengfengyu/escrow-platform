package com.escrow.platform.common.security.application;

import com.escrow.platform.common.security.domain.SecurityEvent;
import com.escrow.platform.common.security.infrastructure.mapper.SecurityEventMapper;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 安全事件记录器测试（V7）：字段落库、超长截断、记录失败不影响主流程。
 *
 * @since V7
 */
@ExtendWith(MockitoExtension.class)
class SecurityEventRecorderTest {

    @Mock
    private SecurityEventMapper securityEventMapper;

    @InjectMocks
    private SecurityEventRecorder recorder;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void login() {
        LoginUser user = new LoginUser(1001L, "worker@example.com", UserRole.WORKER,
                IdentityType.WORKER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    /** 记录管理员拒绝事件：账号、身份、方法、路径、原因、IP 全部落库。 */
    @Test
    void recordShouldPersistRequestContext() {
        login();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/disputes");
        request.setRemoteAddr("10.0.0.8");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        recorder.recordDenied("无权查看或操作该争议单", "dispute", 60L);

        ArgumentCaptor<SecurityEvent> captor = ArgumentCaptor.forClass(SecurityEvent.class);
        verify(securityEventMapper).insert(captor.capture());
        SecurityEvent event = captor.getValue();
        assertThat(event.getUserId()).isEqualTo(1001L);
        assertThat(event.getRole()).isEqualTo("WORKER");
        assertThat(event.getMethod()).isEqualTo("GET");
        assertThat(event.getPath()).isEqualTo("/api/admin/disputes");
        assertThat(event.getReason()).isEqualTo("无权查看或操作该争议单");
        assertThat(event.getTargetType()).isEqualTo("dispute");
        assertThat(event.getTargetId()).isEqualTo(60L);
        assertThat(event.getIp()).isEqualTo("10.0.0.8");
        assertThat(event.getCreatedAt()).isNotNull();
    }

    /** 超长路径与原因截断到列宽，避免插入失败。 */
    @Test
    void recordShouldTruncateLongText() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/" + "x".repeat(400));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        recorder.recordDenied("y".repeat(800));

        ArgumentCaptor<SecurityEvent> captor = ArgumentCaptor.forClass(SecurityEvent.class);
        verify(securityEventMapper).insert(captor.capture());
        assertThat(captor.getValue().getPath()).hasSize(255);
        assertThat(captor.getValue().getReason()).hasSize(500);
        assertThat(captor.getValue().getUserId()).isNull();
    }

    /** X-Forwarded-For 取首段作为来源 IP。 */
    @Test
    void recordShouldUseFirstForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        recorder.recordDenied("拒绝");

        ArgumentCaptor<SecurityEvent> captor = ArgumentCaptor.forClass(SecurityEvent.class);
        verify(securityEventMapper).insert(captor.capture());
        assertThat(captor.getValue().getIp()).isEqualTo("203.0.113.9");
    }

    /** 落库失败必须吞掉，不能覆盖原始 403。 */
    @Test
    void recordShouldNeverThrow() {
        when(securityEventMapper.insert(any(SecurityEvent.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> recorder.recordDenied("拒绝")).doesNotThrowAnyException();
    }
}
