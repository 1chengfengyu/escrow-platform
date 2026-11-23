package com.escrow.platform.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局 Long 字符串化单测（v1-c6 §8 第 3 条，docs/decisions.md 组三 #14 后附决策）。
 *
 * <p>不走 Spring 上下文：直接拿 {@link Jackson2ObjectMapperBuilder} 应用
 * {@link JacksonConfig} 的定制器，验证的就是生产里那份配置本身。</p>
 *
 * <p>为什么要连 {@code long} 基本类型一起断言：只处理 {@code Long} 包装类时，
 * DTO 里的 {@code long}（金额分、计数）仍是数字，「全局字符串化」就是假全局——
 * 而金额恰恰是最不该在前端变成浮点数的字段。</p>
 *
 * @since V1
 */
class JacksonLongTest {

    private final ObjectMapper objectMapper = buildObjectMapper();

    private record Payload(Long id, long count, String name, Long empty) {
    }

    private static ObjectMapper buildObjectMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().longToStringCustomizer().customize(builder);
        return builder.build();
    }

    /** {@code Long} 包装类序列化为 JSON 字符串。 */
    @Test
    void wrapperLongShouldBeString() throws Exception {
        String json = objectMapper.writeValueAsString(new Payload(123456789012345678L, 0L, null, null));

        assertThat(json).contains("\"id\":\"123456789012345678\"");
    }

    /** {@code long} 基本类型同样序列化为字符串。 */
    @Test
    void primitiveLongShouldBeString() throws Exception {
        String json = objectMapper.writeValueAsString(new Payload(null, 5L, null, null));

        assertThat(json).contains("\"count\":\"5\"");
    }

    /** 其他类型不受影响：{@code String} 仍是字符串，空 {@code Long} 仍是 {@code null}。 */
    @Test
    void otherTypesShouldNotChange() throws Exception {
        String json = objectMapper.writeValueAsString(new Payload(null, 0L, "abc", null));

        assertThat(json)
                .contains("\"name\":\"abc\"")
                .contains("\"empty\":null");
    }
}
