package com.escrow.platform.identity.application;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.SecurityLog;
import com.escrow.platform.identity.domain.IdentityStatus;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.PlatformUserIdentity;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserIdentityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 切换身份单测（FR-ACCT.ROLE.02 / ROLE.03）。
 *
 * <p>核心断言是「只改会话」：切换成功时不许写 {@code platform_user_identity}，
 * 否则会把会话级状态升级成账号级状态，ROLE.03「不影响其他会话」立刻失效。</p>
 *
 * @since V1
 */
@ExtendWith(MockitoExtension.class)
class IdentitySwitchServiceTest {

    private static final LoginUser CURRENT =
            new LoginUser(1001L, "13800138000", UserRole.WORKER, IdentityType.WORKER);

    @Mock
    private PlatformUserIdentityMapper platformUserIdentityMapper;

    @Mock
    private SessionAuthenticator sessionAuthenticator;

    @Mock
    private SecurityLog securityLog;

    @InjectMocks
    private IdentitySwitchService identitySwitchService;

    /** 切到已开通身份：返回新主体、写回上下文、只动会话不写库。 */
    @Test
    void shouldSwitchToOpenedIdentity() {
        when(platformUserIdentityMapper.selectOne(any()))
                .thenReturn(identity(IdentityType.EMPLOYER, IdentityStatus.ACTIVE));

        LoginUser switched = identitySwitchService.switchIdentity(CURRENT, "EMPLOYER");

        assertThat(switched.currentIdentity()).isEqualTo(IdentityType.EMPLOYER);
        assertThat(switched.defaultRole()).isEqualTo(UserRole.WORKER);
        assertThat(switched.userId()).isEqualTo(1001L);

        ArgumentCaptor<LoginUser> captor = ArgumentCaptor.forClass(LoginUser.class);
        verify(sessionAuthenticator).authenticate(captor.capture());
        assertThat(captor.getValue()).isEqualTo(switched);
        verify(platformUserIdentityMapper, never()).insert(any(PlatformUserIdentity.class));
        verify(securityLog).log(SecurityLog.ACTION_SWITCH_IDENTITY, "identity:EMPLOYER",
                "13800138000", SecurityLog.RESULT_SUCCESS);
    }

    /** 身份未开通：403 IDENTITY_NOT_AVAILABLE + 一条拒绝日志（ROLE.02）。 */
    @Test
    void shouldRejectWhenIdentityNotOpened() {
        when(platformUserIdentityMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> identitySwitchService.switchIdentity(CURRENT, "EMPLOYER"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.IDENTITY_NOT_AVAILABLE);

        verify(sessionAuthenticator, never()).authenticate(any());
        verify(securityLog).log(SecurityLog.ACTION_SWITCH_IDENTITY, "identity:EMPLOYER",
                "13800138000", SecurityLog.RESULT_DENIED);
    }

    /** 身份已禁用：与未开通同一出口（403），不泄露是「没开通」还是「被禁用」。 */
    @Test
    void shouldRejectWhenIdentityDisabled() {
        when(platformUserIdentityMapper.selectOne(any()))
                .thenReturn(identity(IdentityType.EMPLOYER, IdentityStatus.DISABLED));

        assertThatThrownBy(() -> identitySwitchService.switchIdentity(CURRENT, "EMPLOYER"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.IDENTITY_NOT_AVAILABLE);

        verify(sessionAuthenticator, never()).authenticate(any());
    }

    /** ADMIN 不允许自助切换：400 INVALID_PARAMETER，且根本不查库。 */
    @Test
    void shouldRejectAdminWithInvalidParameter() {
        assertThatThrownBy(() -> identitySwitchService.switchIdentity(CURRENT, "ADMIN"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);

        verify(platformUserIdentityMapper, never()).selectOne(any());
        verify(sessionAuthenticator, never()).authenticate(any());
    }

    private static PlatformUserIdentity identity(IdentityType type, IdentityStatus status) {
        PlatformUserIdentity identity = new PlatformUserIdentity();
        identity.setUserId(1001L);
        identity.setIdentityType(type);
        identity.setStatus(status);
        return identity;
    }
}
