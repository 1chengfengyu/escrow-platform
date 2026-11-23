package com.escrow.platform.common.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 任务详情三级缓存的本地层与序列化器配置（v2-c4，docs/decisions.md #49）。
 *
 * <p>只负责两件事：Caffeine 本地缓存实例、Redis JSON 编解码器。读取编排、单飞、
 * 降级与 TTL 抖动数值全部在 {@code TaskDetailCache} 里，配置类不掺业务。</p>
 *
 * <p><b>本地层 TTL 也带抖动</b>：{@code expireAfter} 用自定义 {@link Expiry}，
 * 每个条目写入时调用同一处 {@link #ttlWithJitter(long)}，避免同批 key 同时过期。
 * 读取不续期（{@code expireAfterRead} 返回剩余时长），缓存是 60 秒内的短期快照，
 * 不做滑动窗口。</p>
 *
 * <p>本地缓存值类型用 {@code Object}：配置类处在 {@code common}，不能反向依赖
 * {@code task} 域的缓存类型；实际只由 {@code CaffeineTaskDetailCacheStore} 写入快照。</p>
 *
 * @since V2
 */
@Configuration
public class CacheConfig {

    /** 本地层基准 TTL：60 秒。 */
    public static final long LOCAL_TTL_SECONDS = 60L;

    /** 本地层条目上限。 */
    public static final long LOCAL_MAX_SIZE = 1000L;

    /** 抖动区间为基准值的 10%。 */
    private static final long JITTER_DIVISOR = 10L;

    /**
     * 本地 Caffeine 缓存实例。
     *
     * @return 键为任务 ID、值为缓存对象的本地缓存
     */
    @Bean
    public Cache<Long, Object> taskDetailLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(LOCAL_MAX_SIZE)
                .expireAfter(new Expiry<Long, Object>() {
                    @Override
                    public long expireAfterCreate(Long key, Object value, long currentTime) {
                        return TimeUnit.SECONDS.toNanos(ttlWithJitter(LOCAL_TTL_SECONDS));
                    }

                    @Override
                    public long expireAfterUpdate(Long key, Object value, long currentTime,
                                                  long currentDuration) {
                        return TimeUnit.SECONDS.toNanos(ttlWithJitter(LOCAL_TTL_SECONDS));
                    }

                    @Override
                    public long expireAfterRead(Long key, Object value, long currentTime,
                                               long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    /**
     * Redis 缓存用的 JSON 编解码器。
     *
     * <p>复用 Boot 自动配置的 {@link ObjectMapper}（已注册 JavaTimeModule 与
     * Long→String 全局契约），因此快照里的时间与 19 位雪花 ID 都能安全往返。</p>
     *
     * @param objectMapper 全局 ObjectMapper
     * @return 编解码器
     */
    @Bean
    public JsonCodec taskDetailJsonCodec(ObjectMapper objectMapper) {
        return new JsonCodec(objectMapper);
    }

    /**
     * 给基准 TTL 加 ±10% 以内的正向抖动，且只在此一处实现（卡片 §4.3 / §8.7）。
     *
     * <p>返回值为整数，落在 {@code [base, base * 1.1)} 区间：偏移量取
     * {@code [0, base/10)}，故上界严格小于 {@code base + base/10}。</p>
     *
     * @param base 基准值（秒或毫秒，单位由调用方决定）
     * @return 抖动后的值
     */
    public static long ttlWithJitter(long base) {
        long span = base / JITTER_DIVISOR;
        if (span <= 0) {
            return base;
        }
        return base + ThreadLocalRandom.current().nextLong(span);
    }

    /** JSON 编解码器；与具体缓存类型解耦，只依赖 ObjectMapper。 */
    public static final class JsonCodec {

        private final ObjectMapper objectMapper;

        JsonCodec(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        public String toJson(Object value) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("task detail cache serialize failed", e);
            }
        }

        public <T> T fromJson(String json, Class<T> type) {
            try {
                return objectMapper.readValue(json, type);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("task detail cache deserialize failed", e);
            }
        }
    }
}
