package com.escrow.platform.common.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流器单测（NFR-SEC.06）：只验证边界判定与键 / TTL 的形状，不连 Redis。
 *
 * <p>真实 Lua 的原子性（INCR 与 PEXPIRE 同脚本）由代码固定，测试无法代替运行态；
 * 集成验收要求在真实 Redis 上验证 429 与 {@code Retry-After}。</p>
 *
 * @since V1
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    private static final String PREFIX = "escrow:rl:login:acct:abc";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private RateLimiter rateLimiter;

    /** 计数未到上限：放行。 */
    @Test
    void shouldAllowWhenCountBelowLimit() {
        stubCount(3L);

        RateLimitResult result = rateLimiter.acquire(PREFIX, 10, WINDOW);

        assertThat(result.allowed()).isTrue();
        assertThat(result.count()).isEqualTo(3L);
        assertThat(result.limit()).isEqualTo(10);
    }

    /** 计数正好等于上限：仍放行（10 次/分钟意味着第 10 次合法）。 */
    @Test
    void shouldAllowWhenCountEqualsLimit() {
        stubCount(10L);

        RateLimitResult result = rateLimiter.acquire(PREFIX, 10, WINDOW);

        assertThat(result.allowed()).isTrue();
    }

    /** 计数超过上限：拒绝（第 11 次）。 */
    @Test
    void shouldRejectWhenCountExceedsLimit() {
        stubCount(11L);

        RateLimitResult result = rateLimiter.acquire(PREFIX, 10, WINDOW);

        assertThat(result.allowed()).isFalse();
        assertThat(result.count()).isEqualTo(11L);
    }

    /** retryAfter 必须落在 {@code (0, window]}：给 0 会让客户端立刻重试，给超窗值会白等。 */
    @Test
    void shouldReturnRetryAfterWithinWindow() {
        stubCount(11L);

        RateLimitResult result = rateLimiter.acquire(PREFIX, 10, WINDOW);

        assertThat(result.retryAfterSeconds()).isGreaterThan(0L);
        assertThat(result.retryAfterSeconds()).isLessThanOrEqualTo(WINDOW.toSeconds());
    }

    /** 键名带窗口桶、脚本收到的 TTL 是窗口毫秒数：换窗口自然换键。 */
    @Test
    @SuppressWarnings("unchecked")
    void shouldPutWindowBucketInKeyAndTtlInArgument() {
        stubCount(1L);

        rateLimiter.acquire(PREFIX, 10, WINDOW);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(
                ArgumentMatchers.<RedisScript<Long>>any(), keysCaptor.capture(), eq("60000"));
        assertThat(keysCaptor.getValue()).hasSize(1);
        assertThat(keysCaptor.getValue().get(0)).matches("escrow:rl:login:acct:abc:\\d+");
    }

    /** Lua 返回 null（理论上不会发生）按 0 处理，不抛 NPE，避免限流器把可用性变成单点。 */
    @Test
    void shouldTreatNullCountAsZero() {
        when(stringRedisTemplate.execute(
                ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class)))
                .thenReturn(null);

        RateLimitResult result = rateLimiter.acquire(PREFIX, 10, WINDOW);

        assertThat(result.allowed()).isTrue();
        assertThat(result.count()).isZero();
    }

    private void stubCount(Long count) {
        when(stringRedisTemplate.execute(
                ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class)))
                .thenReturn(count);
    }
}
