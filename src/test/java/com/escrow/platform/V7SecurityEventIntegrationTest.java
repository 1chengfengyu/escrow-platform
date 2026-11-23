package com.escrow.platform;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.application.SecurityEventQueryService;
import com.escrow.platform.common.security.application.SecurityEventRecorder;
import com.escrow.platform.common.security.domain.SecurityEvent;
import com.escrow.platform.common.security.infrastructure.mapper.SecurityEventMapper;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 安全事件真库测试（V7，NFR-SEC.08）：越权被拒 → 落库 → 管理端按账号检索。
 *
 * <p>记录器依赖 {@code RequestContextHolder} 与 {@code SecurityContextHolder}，
 * 这里手工装配，等价于过滤器链里的真实上下文。</p>
 *
 * @since V7
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V7SecurityEventIntegrationTest {

    private static final long WORKER_ID = 2401L;
    private static final LoginUser WORKER =
            new LoginUser(WORKER_ID, "worker-sec@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);

    @Autowired
    private SecurityEventRecorder securityEventRecorder;
    @Autowired
    private SecurityEventQueryService securityEventQueryService;
    @Autowired
    private SecurityEventMapper securityEventMapper;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        securityEventMapper.delete(null);
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    /** 越权被拒 → 事件落库，管理端按账号 + 时间窗能查到，IP 取 X-Forwarded-For 首段。 */
    @Test
    void deniedAccessShouldBeRecordedAndQueryable() {
        loginAs(WORKER);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/audit");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        securityEventRecorder.recordDenied("仅管理员可访问", "AUDIT", 42L);

        List<SecurityEvent> rows = securityEventMapper.selectList(null);
        assertThat(rows).hasSize(1);
        SecurityEvent saved = rows.get(0);
        assertThat(saved.getUserId()).isEqualTo(WORKER_ID);
        assertThat(saved.getRole()).isEqualTo(IdentityType.WORKER.name());
        assertThat(saved.getMethod()).isEqualTo("GET");
        assertThat(saved.getPath()).isEqualTo("/api/admin/audit");
        assertThat(saved.getIp()).isEqualTo("203.0.113.9");
        assertThat(saved.getTargetId()).isEqualTo(42L);

        PageView<?> page = securityEventQueryService.list(ADMIN, WORKER_ID, null, null, 1, 10);
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.items()).hasSize(1);
    }

    /** 非管理员查询事件列表必须 403，即使事件里全是自己。 */
    @Test
    void nonAdminQueryShouldBeForbidden() {
        loginAs(WORKER);
        securityEventRecorder.recordDenied("FORBIDDEN");

        assertThatThrownBy(() -> securityEventQueryService.list(WORKER, WORKER_ID, null, null, 1, 10))
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
