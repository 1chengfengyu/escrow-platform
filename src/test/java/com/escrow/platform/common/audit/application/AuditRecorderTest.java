package com.escrow.platform.common.audit.application;

import com.escrow.platform.common.audit.domain.AuditLog;
import com.escrow.platform.common.audit.infrastructure.mapper.AuditLogMapper;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 审计记录器测试（V7）：操作人取自会话、系统任务为空、超长截断。
 *
 * @since V7
 */
@ExtendWith(MockitoExtension.class)
class AuditRecorderTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    @InjectMocks
    private AuditRecorder auditRecorder;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** 会话用户写入操作人与身份。 */
    @Test
    void recordShouldUseSessionOperator() {
        LoginUser admin = new LoginUser(9001L, "admin@example.com", UserRole.ADMIN,
                IdentityType.ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, List.of()));

        auditRecorder.record("DISPUTE", "60", "RESOLVE", "DISPUTE_PENDING",
                "DISPUTE_RESOLVED", "basis=证据充分");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLog log = captor.getValue();
        assertThat(log.getOperatorId()).isEqualTo(9001L);
        assertThat(log.getOperatorRole()).isEqualTo("ADMIN");
        assertThat(log.getBizType()).isEqualTo("DISPUTE");
        assertThat(log.getBizNo()).isEqualTo("60");
        assertThat(log.getFromStatus()).isEqualTo("DISPUTE_PENDING");
        assertThat(log.getToStatus()).isEqualTo("DISPUTE_RESOLVED");
        assertThat(log.getCreatedAt()).isNotNull();
    }

    /** 系统任务没有会话，操作人为空。 */
    @Test
    void recordAsShouldAllowSystemOperator() {
        auditRecorder.recordAs(null, "PAYOUT", "700", "UNKNOWN", "重试超时");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getOperatorId()).isNull();
        assertThat(captor.getValue().getAction()).isEqualTo("UNKNOWN");
    }

    /** 超长说明截断到 500，避免插入失败。 */
    @Test
    void recordShouldTruncateDetail() {
        auditRecorder.record("PAYOUT", "700", "SUCCESS", null, "PAYOUT_SUCCESS", "x".repeat(800));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getDetail()).hasSize(500);
    }
}
