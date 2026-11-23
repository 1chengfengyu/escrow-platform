package com.escrow.platform.common.redis;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Redis 固定窗口计数器限流（NFR-SEC.06，v1-c4）。
 *
 * <p>为什么用 Lua 而不是「先 INCR 再 PEXPIRE」两条命令：进程若在两条命令之间退出，
 * 键会永远没有 TTL，该账号 / IP 就被永久限死。放进一段脚本后 Redis 单线程执行，
 * 不存在中间态。</p>
 *
 * <p>为什么键名带窗口桶 {@code {prefix}:{epochSecond / windowSeconds}}：固定窗口计数最简单，
 * 但若所有请求共用一个键，就得在读取时判断「这个键属于哪个窗口」。把窗口编号写进键名，
 * 换窗口自然换键，旧键靠 TTL 自动回收，读取路径不用做任何时间判断。</p>
 *
 * <p>只允许 {@link StringRedisTemplate}：不 new Jedis、不自己拿 {@code RedisConnection}、
 * 不引 Redisson（docs/tech-stack.md）。</p>
 *
 * @since V1
 */
@Component
@RequiredArgsConstructor
public class RateLimiter {

    /**
     * INCR 与设置 TTL 必须在同一段脚本里：首次写入即设置过期时间，
     * 之后每次只加一，过期的键被 Redis 自动删除后重新从 1 开始。
     */
    private static final String INCR_WITH_TTL_LUA = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return current
            """;

    private static final DefaultRedisScript<Long> INCR_WITH_TTL =
            new DefaultRedisScript<>(INCR_WITH_TTL_LUA, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 对 {@code keyPrefix} 对应的窗口计数一次，并返回是否放行。
     *
     * @param keyPrefix 业务键前缀（不含窗口桶），如 {@code escrow:rl:login:acct:13800138000}
     * @param limit     本窗口允许的最大请求数
     * @param window    窗口长度
     * @return 判定结果；{@code count == limit} 仍放行，{@code count > limit} 拒绝
     */
    public RateLimitResult acquire(String keyPrefix, int limit, Duration window) {
        long windowSeconds = Math.max(window.toSeconds(), 1L);
        long epochSecond = Instant.now().getEpochSecond();
        long bucket = epochSecond / windowSeconds;
        String key = keyPrefix + ":" + bucket;

        Long count = stringRedisTemplate.execute(
                INCR_WITH_TTL, List.of(key), String.valueOf(window.toMillis()));
        long current = count == null ? 0L : count;
        // 当前窗口还剩多少秒：[1, windowSeconds]
        long retryAfter = windowSeconds - (epochSecond % windowSeconds);
        return new RateLimitResult(current <= limit, current, limit, retryAfter);
    }

    /**
     * 计数并放行，超限直接抛 {@code RATE_LIMITED}（429 + {@code data.retryAfter}）。
     *
     * <p>把「429 的形状」收在这一处：四个限流点若各写一遍 {@code Map.of("retryAfter", ...)}，
     * 改字段名时必漏。</p>
     *
     * @param keyPrefix 业务键前缀
     * @param limit     本窗口允许的最大请求数
     * @param window    窗口长度
     * @throws ApiException 本窗口计数已超限
     */
    public void checkOrThrow(String keyPrefix, int limit, Duration window) {
        RateLimitResult result = acquire(keyPrefix, limit, window);
        if (!result.allowed()) {
            throw new ApiException(ErrorCode.RATE_LIMITED, ErrorCode.RATE_LIMITED.getDefaultMessage(),
                    Map.of("retryAfter", result.retryAfterSeconds()));
        }
    }
}
