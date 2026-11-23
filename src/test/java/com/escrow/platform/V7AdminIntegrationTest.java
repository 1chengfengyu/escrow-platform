package com.escrow.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.admin.api.AdminDashboardView;
import com.escrow.platform.admin.application.AdminDashboardService;
import com.escrow.platform.common.audit.domain.AuditLog;
import com.escrow.platform.common.audit.infrastructure.mapper.AuditLogMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.api.AdminUserStatusRequest;
import com.escrow.platform.identity.api.AdminUserView;
import com.escrow.platform.identity.application.AdminUserService;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.PlatformUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.identity.domain.UserStatus;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserMapper;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理端账号治理与看板真库测试（V7，FR-ADMIN.USER.01 / .02 / FR-ADMIN.DASH.01）。
 *
 * <p>验证：禁用落库、审计可查、启用恢复、非管理员 403、看板计数为非负。</p>
 *
 * @since V7
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V7AdminIntegrationTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);
    private static final LoginUser WORKER =
            new LoginUser(1002L, "worker-admin-it@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final String TARGET_PHONE = "13900009999";

    @Autowired
    private AdminUserService adminUserService;
    @Autowired
    private AdminDashboardService adminDashboardService;
    @Autowired
    private PlatformUserMapper platformUserMapper;
    @Autowired
    private AuditLogMapper auditLogMapper;

    private Long targetUserId;

    @BeforeEach
    void setUp() {
        cleanUp();
        PlatformUser target = new PlatformUser();
        target.setPhone(TARGET_PHONE);
        target.setEmail("user-admin-it@example.com");
        target.setNickname("待治理账号");
        target.setPasswordHash("$2A$10$abcdefghijklmnopqrstuv");
        target.setRole(UserRole.WORKER);
        target.setStatus(UserStatus.ACTIVE);
        platformUserMapper.insert(target);
        targetUserId = target.getId();
    }

    @AfterEach
    void cleanUp() {
        if (targetUserId != null) {
            auditLogMapper.delete(new LambdaQueryWrapper<AuditLog>()
                    .eq(AuditLog::getBizType, "USER")
                    .eq(AuditLog::getBizNo, String.valueOf(targetUserId)));
            platformUserMapper.deleteById(targetUserId);
            targetUserId = null;
        }
        SecurityContextHolder.clearContext();
    }

    /** 关键字检索命中新账号，禁用后状态落库且审计可查，启用后恢复 ACTIVE。 */
    @Test
    void disableAndEnableShouldPersistStatusAndAudit() {
        loginAs(ADMIN);

        PageView<AdminUserView> page = adminUserService.list(ADMIN, TARGET_PHONE, null, 1, 10);
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.items().get(0).userId()).isEqualTo(targetUserId);

        AdminUserView disabled = adminUserService.disable(ADMIN, targetUserId,
                new AdminUserStatusRequest("发布违规内容"));
        assertThat(disabled.status()).isEqualTo("DISABLED");
        assertThat(platformUserMapper.selectById(targetUserId).getStatus()).isEqualTo(UserStatus.DISABLED);

        List<AuditLog> audits = auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getBizType, "USER")
                .eq(AuditLog::getBizNo, String.valueOf(targetUserId))
                .eq(AuditLog::getAction, "DISABLE"));
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getFromStatus()).isEqualTo("ACTIVE");
        assertThat(audits.get(0).getToStatus()).isEqualTo("DISABLED");
        assertThat(audits.get(0).getOperatorId()).isEqualTo(ADMIN.userId());
        assertThat(audits.get(0).getDetail()).isEqualTo("发布违规内容");

        AdminUserView enabled = adminUserService.enable(ADMIN, targetUserId,
                new AdminUserStatusRequest("申诉通过，恢复账号"));
        assertThat(enabled.status()).isEqualTo("ACTIVE");
        assertThat(platformUserMapper.selectById(targetUserId).getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    /** 非管理员：列表、禁用、看板全部 403。 */
    @Test
    void nonAdminShouldBeForbidden() {
        loginAs(WORKER);

        assertThatThrownBy(() -> adminUserService.list(WORKER, null, null, 1, 10))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> adminUserService.disable(WORKER, targetUserId,
                new AdminUserStatusRequest("越权操作")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> adminDashboardService.overview(WORKER))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    /** 看板五项计数取自真库，至少包含刚建的账号。 */
    @Test
    void dashboardShouldAggregateRealCounts() {
        loginAs(ADMIN);
        AdminDashboardView view = adminDashboardService.overview(ADMIN);

        assertThat(view.userCount()).isGreaterThanOrEqualTo(1L);
        assertThat(view.taskCount()).isGreaterThanOrEqualTo(0L);
        assertThat(view.pendingPayoutCount()).isGreaterThanOrEqualTo(0L);
        assertThat(view.pendingDisputeCount()).isGreaterThanOrEqualTo(0L);
        assertThat(view.pendingDifferenceCount()).isGreaterThanOrEqualTo(0L);
    }

    private static void loginAs(LoginUser principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
