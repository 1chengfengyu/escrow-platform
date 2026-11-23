package com.escrow.platform.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 登录服务（FR-ACCT.LOGIN.01~LOGIN.03、FR-ACCT.STATUS.01、NFR-SEC.06）。
 *
 * <p>处理顺序本身就是需求，不许调换：IP 限流 → 账号限流 → 锁定检查 → 查账号 → 账号不存在 →
 * 账号禁用 → 口令校验 → 成功。先查库后限流会让「不存在」与「口令错」的耗时差变成探测信号；
 * 锁定检查放在口令校验之前，锁定期间连哈希都不算（省 CPU，也避免把正确口令当成解锁信号）。</p>
 *
 * <p>对不存在的账号同样计数与锁定：否则「第 5 次变 423」本身就是账号存在的证明，
 * 与 LOGIN.03「不泄露账号是否存在」直接冲突。</p>
 *
 * @since V1
 */
@Service
@RequiredArgsConstructor
public class LoginService {

    /** 登录限流：账号 10 次 / 分钟、IP 30 次 / 分钟（NFR-SEC.06）。 */
    private static final int IP_LIMIT = 30;
    private static final int ACCOUNT_LIMIT = 10;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    /** 连续失败上限与锁定时长（FR-ACCT.LOGIN.02）。 */
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK_TTL = Duration.ofMinutes(15);

    private static final String RATE_IP_KEY_PREFIX = "escrow:rl:login:ip:";
    private static final String RATE_ACCOUNT_KEY_PREFIX = "escrow:rl:login:acct:";
    private static final String FAIL_KEY_PREFIX = "escrow:login:fail:";
    private static final String LOCK_KEY_PREFIX = "escrow:login:lock:";

    private static final String LOGIN_FAILED_MESSAGE = "账号或口令不正确";
    private static final String ACCOUNT_DISABLED_MESSAGE = "账号已被禁用";

    /** 失败计数与 TTL 必须原子：与限流同理由，两条命令之间崩溃会让计数永不过期。 */
    private static final String INCR_WITH_TTL_LUA = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return current
            """;
    private static final DefaultRedisScript<Long> INCR_WITH_TTL =
            new DefaultRedisScript<>(INCR_WITH_TTL_LUA, Long.class);

    private final PlatformUserMapper platformUserMapper;
    private final PlatformUserIdentityMapper platformUserIdentityMapper;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final StringRedisTemplate stringRedisTemplate;
    private final SecurityLog securityLog;
    private final SessionAuthenticator sessionAuthenticator;

    /**
     * 登录并建立会话（FR-ACCT.LOGIN.01）。
     *
     * @param request 登录请求
     * @param ip      调用方 IP，取 {@code request.getRemoteAddr()}；不读 {@code X-Forwarded-For}（可伪造）
     * @return 登录者视图；会话上下文已写入 {@code SecurityContextHolder}，由过滤器在请求结束时落 Redis
     * @throws ApiException 429 限流 / 423 锁定 / 401 账号或口令不正确 / 403 账号禁用
     */
    public LoginResponse login(LoginRequest request, String ip) {
        String account = request.account() == null ? "" : request.account().trim();
        // 邮箱大小写不敏感、手机号不受影响：统一小写后作为 Redis 键的一部分
        String accountKey = account.toLowerCase(Locale.ROOT);
        // 口令可能为 null：BCrypt 的 matches 对 null 直接抛 IllegalArgumentException，这里先归一成空串
        String password = request.password() == null ? "" : request.password();

        rejectIfLimited(rateLimiter.acquire(RATE_IP_KEY_PREFIX + ip, IP_LIMIT, RATE_WINDOW), accountKey);
        rejectIfLimited(rateLimiter.acquire(RATE_ACCOUNT_KEY_PREFIX + accountKey, ACCOUNT_LIMIT, RATE_WINDOW),
                accountKey);

        // 锁定期间连口令都不校验：不给「试到对不对」留任何时间差
        if (isLocked(accountKey)) {
            securityLog.log(SecurityLog.ACTION_LOGIN, "account", accountKey, SecurityLog.RESULT_LOCKED);
            throw locked();
        }

        PlatformUser user = findByAccount(account);
        if (user == null) {
            securityLog.log(SecurityLog.ACTION_LOGIN, "account", accountKey, SecurityLog.RESULT_FAILED);
            recordFailure(accountKey);
            throw unauthenticated();
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            // STATUS.01：禁用账号禁止登录；此分支不计数，否则会把「被封禁」伪装成「口令错」
            securityLog.log(SecurityLog.ACTION_LOGIN, "account", accountKey, SecurityLog.RESULT_DENIED);
            throw new ApiException(ErrorCode.FORBIDDEN, ACCOUNT_DISABLED_MESSAGE);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            securityLog.log(SecurityLog.ACTION_LOGIN, "account", accountKey, SecurityLog.RESULT_FAILED);
            recordFailure(accountKey);
            throw unauthenticated();
        }

        stringRedisTemplate.delete(FAIL_KEY_PREFIX + accountKey);

        List<PlatformUserIdentity> activeIdentities = activeIdentities(user.getId());
        if (activeIdentities.isEmpty()) {
            // 一个身份都没开通（含全部被禁用）：登录成功也没有可用的「当前身份」，按 ROLE.02 拒绝
            securityLog.log(SecurityLog.ACTION_LOGIN, "identity", accountKey, SecurityLog.RESULT_DENIED);
            throw new ApiException(ErrorCode.IDENTITY_NOT_AVAILABLE,
                    ErrorCode.IDENTITY_NOT_AVAILABLE.getDefaultMessage());
        }
        IdentityType currentIdentity = resolveCurrentIdentity(user.getRole(), activeIdentities);
        LoginUser loginUser = new LoginUser(user.getId(), account, user.getRole(), currentIdentity);
        sessionAuthenticator.authenticate(loginUser);

        securityLog.log(SecurityLog.ACTION_LOGIN, "account", accountKey, SecurityLog.RESULT_SUCCESS);
        return new LoginResponse(user.getId(), user.getNickname(), user.getRole().name(),
                currentIdentity.name(), activeIdentities.stream().map(i -> i.getIdentityType().name()).toList());
    }

    /** 429：写 {action, account} 的拒绝日志，再抛带 {@code retryAfter} 的业务异常。 */
    private void rejectIfLimited(RateLimitResult result, String accountKey) {
        if (result.allowed()) {
            return;
        }
        securityLog.log(SecurityLog.ACTION_LOGIN, "account", accountKey, SecurityLog.RESULT_RATE_LIMITED);
        throw new ApiException(ErrorCode.RATE_LIMITED, ErrorCode.RATE_LIMITED.getDefaultMessage(),
                Map.of("retryAfter", result.retryAfterSeconds()));
    }

    private boolean isLocked(String accountKey) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(LOCK_KEY_PREFIX + accountKey));
    }

    /**
     * 记一次失败；达到 5 次时当次就返回 423 并写锁键（不是等第 6 次）。
     *
     * @param accountKey 小写账号
     * @throws ApiException 本次失败使计数达到上限
     */
    private void recordFailure(String accountKey) {
        Long count = stringRedisTemplate.execute(INCR_WITH_TTL,
                List.of(FAIL_KEY_PREFIX + accountKey), String.valueOf(LOCK_TTL.toMillis()));
        long current = count == null ? 0L : count;
        if (current >= MAX_FAILURES) {
            stringRedisTemplate.opsForValue().set(LOCK_KEY_PREFIX + accountKey, "1", LOCK_TTL);
            securityLog.log(SecurityLog.ACTION_LOGIN, "account", accountKey, SecurityLog.RESULT_LOCKED);
            throw locked();
        }
    }

    /** 含 {@code @} 视为邮箱，否则按手机号查；两类字段都有唯一键，{@code selectOne} 不会命中多行。 */
    private PlatformUser findByAccount(String account) {
        LambdaQueryWrapper<PlatformUser> query = new LambdaQueryWrapper<>();
        if (account.indexOf('@') >= 0) {
            query.eq(PlatformUser::getEmail, account);
        } else {
            query.eq(PlatformUser::getPhone, account);
        }
        return platformUserMapper.selectOne(query);
    }

    private List<PlatformUserIdentity> activeIdentities(Long userId) {
        return platformUserIdentityMapper.selectList(new LambdaQueryWrapper<PlatformUserIdentity>()
                .eq(PlatformUserIdentity::getUserId, userId)
                .eq(PlatformUserIdentity::getStatus, IdentityStatus.ACTIVE));
    }

    /**
     * 默认身份优先：注册时选的身份若已开通就用它，否则退回第一个已开通身份。
     *
     * <p>不直接取 {@code user.role}：身份可能被单独禁用（{@link IdentityStatus}），
     * 登录后的「当前身份」必须落在身份表 ACTIVE 的行上（FR-ACCT.ROLE.02 的前置条件）。</p>
     */
    private static IdentityType resolveCurrentIdentity(UserRole defaultRole, List<PlatformUserIdentity> identities) {
        for (PlatformUserIdentity identity : identities) {
            if (identity.getIdentityType().name().equals(defaultRole.name())) {
                return identity.getIdentityType();
            }
        }
        return identities.get(0).getIdentityType();
    }

    private static ApiException unauthenticated() {
        return new ApiException(ErrorCode.UNAUTHENTICATED, LOGIN_FAILED_MESSAGE);
    }

    private static ApiException locked() {
        return new ApiException(ErrorCode.ACCOUNT_LOCKED, ErrorCode.ACCOUNT_LOCKED.getDefaultMessage());
    }
}
