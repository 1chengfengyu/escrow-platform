package com.escrow.platform.identity.application;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.redis.RateLimiter;
import com.escrow.platform.common.redis.RateLimitResult;
import com.escrow.platform.common.security.SecurityLog;
import com.escrow.platform.identity.api.LoginRequest;
import com.escrow.platform.identity.api.LoginResponse;
import com.escrow.platform.identity.domain.IdentityStatus;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.PlatformUser;
import com.escrow.platform.identity.domain.PlatformUserIdentity;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.identity.domain.UserStatus;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserIdentityMapper;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录服务单测（FR-ACCT.LOGIN.01~LOGIN.03、STATUS.01、NFR-SEC.06）。
 *
 * <p>纯 Mockito：不连 Redis、不连 MySQL。Redis 只用替身验证「计数、锁定、清计数」三次写操作，
 * 真实 Lua 行为由集成测试覆盖。</p>
 *
 * @since V1
 */
@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    private static final String ACCOUNT = "13800138000";
    private static final String FAIL_KEY = "escrow:login:fail:" + ACCOUNT;
    private static final String LOCK_KEY = "escrow:login:lock:" + ACCOUNT;
    private static final Duration LOCK_TTL = Duration.ofMinutes(15);
    private static final String CLIENT_IP = "10.0.0.1";

    @Mock
    private PlatformUserMapper platformUserMapper;

    @Mock
    private PlatformUserIdentityMapper platformUserIdentityMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SecurityLog securityLog;

    @Mock
    private SessionAuthenticator sessionAuthenticator;

    @InjectMocks
    private LoginService loginService;

    /** 正确口令 200 语义：装配主体、清失败计数、返回当前身份（LOGIN.01）。 */
    @Test
    void shouldLoginSuccessfullyAndClearFailureCounter() {
        allowRateLimit();
        when(stringRedisTemplate.hasKey(LOCK_KEY)).thenReturn(false);
        when(platformUserMapper.selectOne(any())).thenReturn(user(UserStatus.ACTIVE));
        when(passwordEncoder.matches("abc12345", "$2a$10$hash")).thenReturn(true);
        when(platformUserIdentityMapper.selectList(any()))
                .thenReturn(List.of(identity(IdentityType.WORKER, IdentityStatus.ACTIVE)));

        LoginResponse response = loginService.login(request(ACCOUNT, "abc12345"), CLIENT_IP);

        assertThat(response.userId()).isEqualTo(1001L);
        assertThat(response.defaultRole()).isEqualTo("WORKER");
        assertThat(response.currentIdentity()).isEqualTo("WORKER");
        assertThat(response.identities()).containsExactly("WORKER");

        verify(stringRedisTemplate).delete(FAIL_KEY);
        ArgumentCaptor<LoginUser> captor = ArgumentCaptor.forClass(LoginUser.class);
        verify(sessionAuthenticator).authenticate(captor.capture());
        assertThat(captor.getValue()).isEqualTo(
                new LoginUser(1001L, ACCOUNT, UserRole.WORKER, IdentityType.WORKER));
        verify(securityLog).log(SecurityLog.ACTION_LOGIN, "account", ACCOUNT, SecurityLog.RESULT_SUCCESS);
    }

    /** 口令错：401，计一次失败，不清计数、不建会话（LOGIN.03）。 */
    @Test
    void shouldReturnUnauthenticatedWhenPasswordWrong() {
        allowRateLimit();
        when(stringRedisTemplate.hasKey(LOCK_KEY)).thenReturn(false);
        when(platformUserMapper.selectOne(any())).thenReturn(user(UserStatus.ACTIVE));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        stubFailureCount(1L);

        assertThatThrownBy(() -> loginService.login(request(ACCOUNT, "wrong-pass"), CLIENT_IP))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);

        verify(stringRedisTemplate, never()).delete(FAIL_KEY);
        verify(sessionAuthenticator, never()).authenticate(any());
    }

    /** 账号不存在：同样 401，且同样计失败——否则「第 5 次变 423」会暴露账号是否存在（LOGIN.03）。 */
    @Test
    @SuppressWarnings("unchecked")
    void shouldCountFailureForUnknownAccount() {
        allowRateLimit();
        when(stringRedisTemplate.hasKey(LOCK_KEY)).thenReturn(false);
        when(platformUserMapper.selectOne(any())).thenReturn(null);
        stubFailureCount(1L);

        assertThatThrownBy(() -> loginService.login(request(ACCOUNT, "abc12345"), CLIENT_IP))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(
                ArgumentMatchers.<RedisScript<Long>>any(), keysCaptor.capture(), any(Object[].class));
        assertThat(keysCaptor.getValue()).containsExactly(FAIL_KEY);
    }

    /** 第 5 次失败：当次就 423，并写 15 分钟锁键（LOGIN.02）。 */
    @Test
    void shouldLockAccountOnFifthFailure() {
        allowRateLimit();
        when(stringRedisTemplate.hasKey(LOCK_KEY)).thenReturn(false);
        when(platformUserMapper.selectOne(any())).thenReturn(user(UserStatus.ACTIVE));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        stubFailureCount(5L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        assertThatThrownBy(() -> loginService.login(request(ACCOUNT, "wrong-pass"), CLIENT_IP))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);

        verify(valueOperations).set(LOCK_KEY, "1", LOCK_TTL);
        verify(securityLog).log(SecurityLog.ACTION_LOGIN, "account", ACCOUNT, SecurityLog.RESULT_LOCKED);
    }

    /** 锁定期间即使口令正确也 423，且不查库、不校验口令（省 CPU，也不泄露口令对错）。 */
    @Test
    void shouldRejectWhenAccountLocked() {
        allowRateLimit();
        when(stringRedisTemplate.hasKey(LOCK_KEY)).thenReturn(true);

        assertThatThrownBy(() -> loginService.login(request(ACCOUNT, "abc12345"), CLIENT_IP))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);

        verify(platformUserMapper, never()).selectOne(any());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    /** 账号禁用：403，且不计数（否则「解封后连续错 3 次」会被误锁）。 */
    @Test
    void shouldReturnForbiddenWhenAccountDisabled() {
        allowRateLimit();
        when(stringRedisTemplate.hasKey(LOCK_KEY)).thenReturn(false);
        when(platformUserMapper.selectOne(any())).thenReturn(user(UserStatus.DISABLED));

        assertThatThrownBy(() -> loginService.login(request(ACCOUNT, "abc12345"), CLIENT_IP))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(stringRedisTemplate, never()).delete(FAIL_KEY);
    }

    /** IP 限流：429 + data.retryAfter，且不进入查库（NFR-SEC.06）。 */
    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnRateLimitedWhenIpExceeded() {
        when(rateLimiter.acquire(startsWith("escrow:rl:login:ip:"), anyInt(), any(Duration.class)))
                .thenReturn(new RateLimitResult(false, 31L, 30, 42L));

        assertThatThrownBy(() -> loginService.login(request(ACCOUNT, "abc12345"), CLIENT_IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                    Map<String, Object> data = (Map<String, Object>) apiException.getData();
                    assertThat(data).containsEntry("retryAfter", 42L);
                });

        verify(platformUserMapper, never()).selectOne(any());
    }

    /** 账号限流：键用 trim + 小写后的账号，邮箱大小写不敏感（NFR-SEC.06）。 */
    @Test
    void shouldReturnRateLimitedWhenAccountExceeded() {
        when(rateLimiter.acquire(startsWith("escrow:rl:login:ip:"), anyInt(), any(Duration.class)))
                .thenReturn(new RateLimitResult(true, 1L, 30, 60L));
        when(rateLimiter.acquire(startsWith("escrow:rl:login:acct:"), anyInt(), any(Duration.class)))
                .thenReturn(new RateLimitResult(false, 11L, 10, 30L));

        assertThatThrownBy(() ->
                loginService.login(new LoginRequest("  User@Example.COM ", "abc12345"), CLIENT_IP))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);

        verify(rateLimiter).acquire(startsWith("escrow:rl:login:acct:user@example.com"),
                eq(10), any(Duration.class));
    }

    private void allowRateLimit() {
        when(rateLimiter.acquire(anyString(), anyInt(), any(Duration.class)))
                .thenReturn(new RateLimitResult(true, 1L, 10, 60L));
    }

    /** 模拟失败计数的 Lua 返回：INCR 后当前值。 */
    private void stubFailureCount(long count) {
        when(stringRedisTemplate.execute(
                ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class)))
                .thenReturn(count);
    }

    private static LoginRequest request(String account, String password) {
        return new LoginRequest(account, password);
    }

    private static PlatformUser user(UserStatus status) {
        PlatformUser user = new PlatformUser();
        user.setId(1001L);
        user.setPhone(ACCOUNT);
        user.setNickname("小明");
        user.setPasswordHash("$2a$10$hash");
        user.setRole(UserRole.WORKER);
        user.setStatus(status);
        return user;
    }

    private static PlatformUserIdentity identity(IdentityType type, IdentityStatus status) {
        PlatformUserIdentity identity = new PlatformUserIdentity();
        identity.setIdentityType(type);
        identity.setStatus(status);
        return identity;
    }
}
