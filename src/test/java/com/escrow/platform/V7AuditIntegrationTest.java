package com.escrow.platform;

import com.escrow.platform.common.audit.application.AuditQueryService;
import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.audit.domain.AuditLog;
import com.escrow.platform.common.audit.infrastructure.mapper.AuditLogMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审计流水真库测试（V7，FR-AUDIT.QUERY.01 / FR-AUDIT.EXPORT.01）。
 *
 * <p>验证四件事：操作人从会话落到 {@code operator_id} / {@code operator_role}；
 * 条件检索命中；CSV 带 BOM 且逗号引号被转义；非管理员一律 403。</p>
 *
 * @since V7
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V7AuditIntegrationTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);
    private static final LoginUser WORKER =
            new LoginUser(2402L, "worker-audit@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Autowired
    private AuditRecorder auditRecorder;
    @Autowired
    private AuditQueryService auditQueryService;
    @Autowired
    private AuditLogMapper auditLogMapper;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        auditLogMapper.delete(null);
        SecurityContextHolder.clearContext();
    }

    /** 管理员处置落审计 → 按业务类型可检索，CSV 含 BOM、表头与转义后的明细。 */
    @Test
    void recordedAuditShouldBeQueryableAndExportable() {
        loginAs(ADMIN);
        auditRecorder.record("PAYOUT", "1001", "CONFIRM_ACTUAL", "PAYOUT_MANUAL_REVIEW",
                "PAYOUT_SUCCESS", "actual=970, remark=\"到账\"");
        auditRecorder.recordAs(0L, "SYSTEM", "job-1", "AGING_ALERT", "count=2");

        PageView<?> page = auditQueryService.list(ADMIN, "PAYOUT", null, ADMIN.userId(),
                null, null, 1, 10);
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.items()).hasSize(1);

        List<AuditLog> rows = auditLogMapper.selectList(null);
        AuditLog manual = rows.stream()
                .filter(row -> "PAYOUT".equals(row.getBizType()))
                .findFirst()
                .orElseThrow();
        assertThat(manual.getOperatorId()).isEqualTo(ADMIN.userId());
        assertThat(manual.getOperatorRole()).isEqualTo(IdentityType.ADMIN.name());
        assertThat(manual.getFromStatus()).isEqualTo("PAYOUT_MANUAL_REVIEW");
        assertThat(manual.getToStatus()).isEqualTo("PAYOUT_SUCCESS");

        AuditLog system = rows.stream()
                .filter(row -> "SYSTEM".equals(row.getBizType()))
                .findFirst()
                .orElseThrow();
        assertThat(system.getOperatorId()).isZero();
        assertThat(system.getOperatorRole()).isNull();

        byte[] csv = auditQueryService.exportCsv(ADMIN, null, null, null, null, null);
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).startsWith("\uFEFF");
        assertThat(text).contains("id,bizType,bizNo,action");
        assertThat(text).contains("PAYOUT,1001,CONFIRM_ACTUAL");
        assertThat(text).contains("\"actual=970, remark=\"\"到账\"\"\"");
    }

    /** 非管理员查询与导出都必须是 403。 */
    @Test
    void nonAdminQueryAndExportShouldBeForbidden() {
        loginAs(WORKER);
        auditRecorder.record("USER", "1", "DISABLE", null, null, null);

        assertThatThrownBy(() -> auditQueryService.list(WORKER, null, null, null, null, null, 1, 10))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> auditQueryService.exportCsv(WORKER, null, null, null, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private static void loginAs(LoginUser principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
