package com.escrow.platform.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.redis.RateLimiter;
import com.escrow.platform.common.web.TraceIdFilter;
import com.escrow.platform.identity.api.RegisterRequest;
import com.escrow.platform.identity.domain.IdentityStatus;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.PlatformUser;
import com.escrow.platform.identity.domain.PlatformUserIdentity;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.identity.domain.UserStatus;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserIdentityMapper;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * 注册服务：校验 → 查重 → 双写账号与默认身份（FR-ACCT.REG.01~REG.03、FR-ACCT.ROLE.01）。
 *
 * <p>「双写」指 {@code platform_user} 与 {@code platform_user_identity} 各插一行：
 * 账号表回答「这个人是谁」，身份表回答「他能以什么身份做事」，业务授权一律以身份表为准
 * （docs/decisions.md #19 文末 V1 修订）。两行必须在同一事务里，否则会出现能登录却没有身份的账号。</p>
 *
 * <p>并发兜底：先查重后插入之间存在竞态，唯一键 {@code uk_phone} / {@code uk_email}
 * 是最终防线；捕获 {@link DuplicateKeyException} 转 {@code ACCOUNT_EXISTS}，
 * 其余 {@code DataAccessException} 继续上抛给全局处理器（转 503）。</p>
 *
 * <p>本卡不做自动登录、不发短信、不写 {@code platform_worker_profile}，限流在 c4。</p>
 *
 * @since V1
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterService {

    private static final String INVALID_PARAMETER_MESSAGE = "请求参数不合法";

    /** 注册限流：IP 5 次 / 分钟、目标（手机号或邮箱）5 次 / 分钟（NFR-SEC.06）。 */
    private static final int REGISTER_LIMIT = 5;
    private static final Duration REGISTER_WINDOW = Duration.ofMinutes(1);
    private static final String REGISTER_IP_KEY_PREFIX = "escrow:rl:reg:ip:";
    private static final String REGISTER_TARGET_KEY_PREFIX = "escrow:rl:reg:target:";

    private final PlatformUserMapper platformUserMapper;
    private final PlatformUserIdentityMapper platformUserIdentityMapper;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;

    /** 无状态校验器，直接持有实例；不必为它加 Spring 注解，注册流程只有这一处使用。 */
    private final RegisterValidator registerValidator = new RegisterValidator();

    /**
     * 注册一个账号并开通其默认身份（FR-ACCT.REG.01~REG.03、FR-ACCT.ROLE.01）。
     *
     * @param req 已由 Controller 反序列化的注册请求
     * @param ip  调用方 IP，取 {@code request.getRemoteAddr()}（不读 {@code X-Forwarded-For}，头可伪造）
     * @return 新账号的雪花 ID
     * @throws ApiException 触发限流（{@code RATE_LIMITED}）、字段不合法（{@code INVALID_PARAMETER}）
     *                      或手机号/邮箱已存在（{@code ACCOUNT_EXISTS}）
     */
    @Transactional
    public Long register(RegisterRequest req, String ip) {
        // NFR-SEC.06：注册口没有账号主体，按 IP + 目标（手机号/邮箱）双键各 5 次/分钟；
        // 目标键只存 SHA-256，避免明文号码出现在 Redis 键、MONITOR 与慢日志里
        rateLimiter.checkOrThrow(REGISTER_IP_KEY_PREFIX + ip, REGISTER_LIMIT, REGISTER_WINDOW);
        rateLimiter.checkOrThrow(REGISTER_TARGET_KEY_PREFIX + sha256Hex(registerTarget(req)),
                REGISTER_LIMIT, REGISTER_WINDOW);

        Map<String, String> fields = registerValidator.validate(req);
        if (!fields.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, INVALID_PARAMETER_MESSAGE,
                    Map.of("fields", fields));
        }

        String phone = blankToNull(req.phone());
        String email = blankToNull(req.email());

        if (phoneExists(phone) || emailExists(email)) {
            throw accountExists();
        }

        PlatformUser user = new PlatformUser();
        user.setPhone(phone);
        user.setEmail(email);
        // 校验按 trim 后判断，落库也存 trim 后的值，否则「长度合格但带前导空格」会撑破 VARCHAR(64)
        user.setNickname(req.nickname().trim());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setRole(UserRole.valueOf(req.role()));
        user.setStatus(UserStatus.ACTIVE);

        try {
            // 雪花 ID 由 MyBatis-Plus 的 assign_id 回填到 user，禁止自己生成
            platformUserMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            // 查重与插入之间被别人抢先注册：唯一键是最终防线
            log.warn("register rejected by duplicate key traceId={}", currentTraceId());
            throw accountExists();
        }

        PlatformUserIdentity identity = new PlatformUserIdentity();
        identity.setUserId(user.getId());
        identity.setIdentityType(IdentityType.valueOf(req.role()));
        identity.setStatus(IdentityStatus.ACTIVE);
        platformUserIdentityMapper.insert(identity);

        // NFR-SEC.04：只打 userId 与链路号，手机号 / 邮箱 / 口令 / 哈希一律不进日志
        log.info("register ok userId={} traceId={}", user.getId(), currentTraceId());
        return user.getId();
    }

    private boolean phoneExists(String phone) {
        if (phone == null) {
            return false;
        }
        return platformUserMapper.selectCount(
                new LambdaQueryWrapper<PlatformUser>().eq(PlatformUser::getPhone, phone)) > 0;
    }

    private boolean emailExists(String email) {
        if (email == null) {
            return false;
        }
        return platformUserMapper.selectCount(
                new LambdaQueryWrapper<PlatformUser>().eq(PlatformUser::getEmail, email)) > 0;
    }

    /** 空白串统一转 {@code null}：库里多个 NULL 不撞唯一键，空串会。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 限流目标：优先手机号，其次邮箱；两者都没填时用空串（仍受 IP 键约束）。 */
    private static String registerTarget(RegisterRequest req) {
        String phone = blankToNull(req.phone());
        if (phone != null) {
            return phone.trim();
        }
        String email = blankToNull(req.email());
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** SHA-256 十六进制（小写）；{@code SHA-256} 是 JVM 必须实现的算法，缺失即环境损坏。 */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    private static ApiException accountExists() {
        return new ApiException(ErrorCode.ACCOUNT_EXISTS, ErrorCode.ACCOUNT_EXISTS.getDefaultMessage());
    }

    private static String currentTraceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        return traceId == null ? "" : traceId;
    }
}
