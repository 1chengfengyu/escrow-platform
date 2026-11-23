package com.escrow.platform.common.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 日志脱敏 Converter 单测（v1-c6 §8 第 1 条，NFR-SEC.04）。
 *
 * <p>不启动 Spring、不碰日志框架初始化：只把 {@link ILoggingEvent} 打成替身，
 * 验证「一条日志消息进 → 打码后的消息出」这一条纯函数契约。</p>
 *
 * @since V1
 */
class DesensitizingConverterTest {

    /** 用替身事件跑一次转换，避免为一条日志消息起整个 Logback 上下文。 */
    private static String convert(String message) {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage()).thenReturn(message);
        return new DesensitizingConverter().convert(event);
    }

    /** 手机号：留前 3 后 4。 */
    @Test
    void phoneShouldBeMasked() {
        assertThat(convert("登录手机号 13800138000")).isEqualTo("登录手机号 138****8000");
    }

    /** 身份证 18 位：留前 6 后 4。 */
    @Test
    void idCardShouldBeMasked() {
        assertThat(convert("证件号 110101199001011234")).isEqualTo("证件号 110101********1234");
    }

    /** 银行卡 19 位：留前 4 后 4。 */
    @Test
    void bankCardShouldBeMasked() {
        assertThat(convert("卡号 6222021234567890123")).isEqualTo("卡号 6222********0123");
    }

    /** 三类混在一行：各自被打码，且原文不再出现（验证规则顺序没有互相吃掉）。 */
    @Test
    void mixedLineShouldMaskAllThree() {
        String masked = convert("phone=13800138000 id=110101199001011234 card=6222021234567890123");

        assertThat(masked)
                .contains("138****8000")
                .contains("110101********1234")
                .contains("6222********0123")
                .doesNotContain("13800138000")
                .doesNotContain("110101199001011234")
                .doesNotContain("6222021234567890123");
    }

    /** 空消息：返回空串，不抛异常（Converter 在每条日志上都会跑，抛异常会炸掉日志线程）。 */
    @Test
    void emptyMessageShouldReturnEmptyString() {
        assertThat(convert(null)).isEmpty();
        assertThat(convert("")).isEmpty();
    }
}
