package com.escrow.platform.common.log;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * 日志脱敏 Converter（v1-c6，NFR-SEC.04）。
 *
 * <p>在 logback-spring.xml 里以 {@code %maskedMsg} 注册，替换掉默认的 {@code %msg}。
 * 这样脱敏发生在「日志事件 → 文本」这一步，任何 appender（控制台 / 文件 / 将来的日志平台）
 * 拿到的都已经是打码后的内容，不依赖业务代码逐处处理。</p>
 *
 * <p>三条规则、顺序固定：<b>身份证 → 银行卡 → 手机号</b>。先长后短，
 * 否则银行卡规则（16~19 位）会先吃掉身份证，手机号规则又会吃掉长数字串的尾部。</p>
 *
 * <p>三条 {@link Pattern} 都是 {@code static final}：{@code convert} 每条日志都会调用，
 * 在里面 {@code Pattern.compile} 等于给每条日志加一次正则编译，是典型的性能事故。</p>
 *
 * @since V1
 */
public class DesensitizingConverter extends ClassicConverter {

    /** 身份证 18 位：留前 6 后 4（末位可能是 X）。 */
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)(\\d{6})(\\d{8})(\\d{3}[\\dXx])(?!\\d)");
    private static final String ID_CARD_REPLACEMENT = "$1********$3";

    /** 银行卡 16~19 位：留前 4 后 4。 */
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)(\\d{4})\\d{8,11}(\\d{4})(?!\\d)");
    private static final String BANK_CARD_REPLACEMENT = "$1********$2";

    /** 手机号 11 位：{@code 138****5678}。 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)");
    private static final String PHONE_REPLACEMENT = "$1****$3";

    /**
     * 把一条日志消息里的敏感数字打码。
     *
     * @param event 日志事件
     * @return 打码后的消息；原消息为 {@code null} 或空串时返回空串（不抛异常）
     */
    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (message == null || message.isEmpty()) {
            return "";
        }
        String masked = ID_CARD.matcher(message).replaceAll(ID_CARD_REPLACEMENT);
        masked = BANK_CARD.matcher(masked).replaceAll(BANK_CARD_REPLACEMENT);
        return PHONE.matcher(masked).replaceAll(PHONE_REPLACEMENT);
    }
}
