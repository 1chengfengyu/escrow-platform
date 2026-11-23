package com.escrow.platform.identity.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.SecurityLog;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.api.AdminUserStatusRequest;
import com.escrow.platform.identity.api.AdminUserView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.PlatformUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.identity.domain.UserStatus;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserMapper;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端账号治理测试（V7，FR-ADMIN.USER.01 / .02）。
 *
 * @since V7
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);
    private static final LoginUser WORKER =
            new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Mock
    private PlatformUserMapper platformUserMapper;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private SecurityLog securityLog;

    @InjectMocks
    private AdminUserService service;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, PlatformUser.class);
    }

    /** 非管理员查询：403。 */
    @Test
    void nonAdminListShouldBeForbidden() {
        assertThatThrownBy(() -> service.list(WORKER, null, null, 1, 20))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(platformUserMapper, never()).selectPage(any(), any());
    }

    /** 管理员查询：返回投影字段与总数。 */
    @Test
    void adminListShouldReturnPage() {
        PlatformUser user = user(1001L, UserStatus.ACTIVE);
        Page<PlatformUser> page = new Page<>(1, 20);
        page.setRecords(List.of(user));
        page.setTotal(1);
        when(platformUserMapper.selectPage(any(), any())).thenReturn(page);

        PageView<AdminUserView> result = service.list(ADMIN, "张", "ACTIVE", 1, 20);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).userId()).isEqualTo(1001L);
        assertThat(result.items().get(0).status()).isEqualTo("ACTIVE");
    }

    /** 非法状态参数：400，不落库。 */
    @Test
    void invalidStatusShouldBeRejected() {
        assertThatThrownBy(() -> service.list(ADMIN, null, "BANNED", 1, 20))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    /** 禁用原因为空：400。 */
    @Test
    void blankReasonShouldBeRejected() {
        assertThatThrownBy(() -> service.disable(ADMIN, 1001L, new AdminUserStatusRequest("  ")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        verify(platformUserMapper, never()).update(any(), any());
    }

    /** 禁用成功：条件更新 + 审计流水记录前后状态与原因。 */
    @Test
    void disableShouldUpdateStatusAndAudit() {
        PlatformUser active = user(1001L, UserStatus.ACTIVE);
        PlatformUser disabled = user(1001L, UserStatus.DISABLED);
        when(platformUserMapper.selectById(1001L)).thenReturn(active, disabled);
        when(platformUserMapper.update(any(), any())).thenReturn(1);

        AdminUserView view = service.disable(ADMIN, 1001L, new AdminUserStatusRequest("发布违规任务"));

        assertThat(view.status()).isEqualTo("DISABLED");
        verify(platformUserMapper).update(any(), any());
        verify(auditRecorder).record(eq("USER"), eq("1001"), eq("DISABLE"),
                eq("ACTIVE"), eq("DISABLED"), eq("发布违规任务"));
    }

    /** 已禁用账号再次禁用：409，不重复写审计。 */
    @Test
    void disableNonActiveShouldConflict() {
        when(platformUserMapper.selectById(1001L)).thenReturn(user(1001L, UserStatus.DISABLED));

        assertThatThrownBy(() -> service.disable(ADMIN, 1001L, new AdminUserStatusRequest("重复操作")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(auditRecorder, never()).record(any(), any(), any(), any(), any(), any());
    }

    /** 注销账号不可启用：409。 */
    @Test
    void enableClosedShouldConflict() {
        when(platformUserMapper.selectById(1001L)).thenReturn(user(1001L, UserStatus.CLOSED));

        assertThatThrownBy(() -> service.enable(ADMIN, 1001L, new AdminUserStatusRequest("恢复账号")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    /** 目标账号不存在：404。 */
    @Test
    void missingUserShouldNotFound() {
        when(platformUserMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.disable(ADMIN, 404L, new AdminUserStatusRequest("违规")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 计数为空时返回 0，不抛 NPE。 */
    @Test
    void countAllShouldTreatNullAsZero() {
        when(platformUserMapper.selectCount(null)).thenReturn(null);
        assertThat(service.countAll()).isZero();
    }

    private static PlatformUser user(Long id, UserStatus status) {
        PlatformUser user = new PlatformUser();
        user.setId(id);
        user.setNickname("张三");
        user.setPhone("13800138000");
        user.setEmail("worker@example.com");
        user.setRole(UserRole.WORKER);
        user.setStatus(status);
        user.setCreatedAt(LocalDateTime.of(2026, 11, 18, 10, 0));
        return user;
    }
}
