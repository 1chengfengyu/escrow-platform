package com.escrow.platform.common.chat.domain;

import java.util.regex.Pattern;

/**
 * 疑似联系方式检测器（V11，补遗 §2.3 / §5.3，FR-MSG.CHAT.03 / NS-11）。
 *
 * <p><b>只标记不拦截</b>：命中与否只用于把 {@code chat_message.contact_hint} 置 1，
 * 消息照常发送、正文不改写、命中原文不入库也不写日志（{@code NFR-SEC.04}）。</p>
 *
 * <p>检测在本类内完成，不依赖 Spring / MyBatis / Jackson，前端不做判定。正则必然漏报，
 * 漏报的代价只是少一次提示，可以接受；误报的代价是发送方多看到一次提示，同样不阻断。</p>
 *
 * <p>手机号与 QQ 号都要求前后不是数字，避免把订单号、任务号等长数字串切成手机号。</p>
 *
 * @since V11
 */
public final class ContactDetector {

    /** 大陆手机号：1 开头、第二位 3~9、共 11 位，前后不得紧邻数字。 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /** 邮箱：必须有 {@code .} 加 2 位以上顶级域，{@code a@b} 不算。 */
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** 微信号：{@code wx/vx/微信} + 可选冒号 + 5~20 位字母数字下划线连字符。 */
    private static final Pattern WECHAT =
            Pattern.compile("(?i)(?<![a-z])(wx|vx|微信)[:：]?\\s*[a-z0-9_-]{5,20}");

    /** QQ 号：{@code qq/扣扣} + 可选冒号 + 5~12 位、首位非 0，前后不得紧邻数字。 */
    private static final Pattern QQ =
            Pattern.compile("(?i)(?<![a-z0-9])(qq|扣扣)[:：]?\\s*[1-9]\\d{4,11}(?!\\d)");

    /** 外部链接：{@code http(s)://} 或 {@code www.} 开头，且后面至少有一个点分段。 */
    private static final Pattern LINK =
            Pattern.compile("(?i)(https?://|www\\.)[a-z0-9-]+(\\.[a-z0-9-]+)+");

    private ContactDetector() {
    }

    /**
     * 判断正文是否疑似包含联系方式。
     *
     * @param content 消息正文，可为 {@code null}
     * @return 命中任一类返回 {@code true}；空白输入返回 {@code false}
     */
    public static boolean hit(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        return PHONE.matcher(content).find()
                || EMAIL.matcher(content).find()
                || WECHAT.matcher(content).find()
                || QQ.matcher(content).find()
                || LINK.matcher(content).find();
    }
}
