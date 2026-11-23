package com.escrow.platform.common.chat.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 联系方式检测器测试（V11，FR-MSG.CHAT.03 / NS-11）。
 *
 * <p>命中与不命中各覆盖 8 例以上：不命中的反例刻意选金额、任务号、日期、
 * 订单长数字串等真实正文里会出现的内容——误报会让用户多看到一次提示，
 * 漏报只是少一次提示，两类偏差都要在测试里锁住。</p>
 *
 * @since V11
 */
class ContactDetectorTest {

    /** 手机号、邮箱、微信、QQ、外部链接各至少一例。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "我电话 13812345678，方便时联系",
            "手机号：15900001111",
            "邮箱 zhangsan.li@example.com 发我",
            "加我微信：wx_lisi2024",
            "wx: abcdef12345",
            "vx lisi_2024",
            "QQ: 123456789",
            "扣扣 87654321",
            "详情见 https://example.com/abc",
            "参考 www.example.org 上的说明"
    })
    void contactHintShouldHit(String content) {
        assertThat(ContactDetector.hit(content)).as(content).isTrue();
    }

    /** 业务正文里的金额、单号、日期、长数字串与半截邮箱不得误判。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "这个任务什么时候开始？",
            "¥1,284.00",
            "任务号 TSK·000431",
            "2026-11-21 下午三点",
            "a@b",
            "订单编号 202311210000123456",
            "第 13800138000123 号",
            "单价 128400 分，共 3 个名额",
            "请通过平台完成签约与结算"
    })
    void normalContentShouldNotHit(String content) {
        assertThat(ContactDetector.hit(content)).as(content).isFalse();
    }

    /** 空串、纯空白与 null 一律不命中。 */
    @Test
    void blankContentShouldNotHit() {
        assertThat(ContactDetector.hit(null)).isFalse();
        assertThat(ContactDetector.hit("")).isFalse();
        assertThat(ContactDetector.hit("   ")).isFalse();
        assertThat(ContactDetector.hit("\n\t")).isFalse();
    }
}
