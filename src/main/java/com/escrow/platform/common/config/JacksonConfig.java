package com.escrow.platform.common.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局契约（v1-c6，依据 docs/decisions.md 组三 #14 后附「全局把 Long 序列化成 JSON 字符串」）。
 *
 * <p>雪花 ID 是 19 位，超过 JavaScript {@code Number.MAX_SAFE_INTEGER}（2^53-1，16 位）。
 * 不字符串化的话，前端 JSON.parse 会把末位抹成 0，再拿它请求详情就查不到——
 * 这是「看起来能跑、偶发查不到」的典型线上事故。</p>
 *
 * <p>只配这一处，不给每个 DTO 字段挂 {@code @JsonSerialize}：字段级注解会随新增 DTO 漏配，
 * 而漏配的那一个正好可能是资金字段。</p>
 *
 * <p>副作用照单全收：{@code long} 基本类型（计数、金额分）也会变成字符串。这是决策原文的行为
 * ——前端本来就不该对金额做算术，字符串能防止任何形式的浮点丢失。</p>
 *
 * @since V1
 */
@Configuration
public class JacksonConfig {

    /**
     * 把 {@code Long} / {@code long} 统一序列化为 JSON 字符串。
     *
     * <p>两个 {@code serializerByType} 都要写：只配包装类时，DTO 里的 {@code long} 依旧是数字，
     * 「全局」就不成立了。</p>
     *
     * @return 注册到 Boot 自动配置 ObjectMapper 上的定制器
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> builder
                .serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance);
    }
}
