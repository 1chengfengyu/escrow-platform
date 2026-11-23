package com.escrow.platform.settlement.infrastructure.config;

import com.escrow.platform.settlement.application.PayoutRuntimeConfig;
import com.escrow.platform.settlement.domain.ChannelMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nacos 配置刷新单测（V5，CR-13）：合法值生效、非法值保留旧值。
 *
 * <p>不连真实 Nacos：只验证「配置文本 → 运行期参数」这一段，
 * 连接与监听属于集成行为，由演示脚本人工验证。</p>
 *
 * @since V5
 */
class NacosConfigRefresherTest {

    private static PayoutRuntimeConfig defaultConfig() {
        return new PayoutRuntimeConfig(3, 2, 10, 1, 1, 30, "SUCCESS");
    }

    /** 合法配置一次刷新多个键。 */
    @Test
    void applyContentShouldUpdateRuntimeConfig() {
        PayoutRuntimeConfig runtimeConfig = defaultConfig();
        NacosConfigRefresher refresher = new NacosConfigRefresher(runtimeConfig);

        refresher.applyContent("max-attempts=5\ngateway.mode=LOST_RESPONSE\naging-warning-minutes=15\n");

        assertThat(runtimeConfig.maxAttempts()).isEqualTo(5);
        assertThat(runtimeConfig.gatewayMode()).isEqualTo(ChannelMode.LOST_RESPONSE);
        assertThat(runtimeConfig.agingWarningMinutes()).isEqualTo(15);
    }

    /** 非法值整批放弃：保留旧值，绝不让在途资金进入错误分支。 */
    @Test
    void invalidContentShouldKeepPreviousValues() {
        PayoutRuntimeConfig runtimeConfig = defaultConfig();
        NacosConfigRefresher refresher = new NacosConfigRefresher(runtimeConfig);

        refresher.applyContent("max-attempts=99\ngateway.mode=SUCCESS\n");

        assertThat(runtimeConfig.maxAttempts()).isEqualTo(3);
        assertThat(runtimeConfig.gatewayMode()).isEqualTo(ChannelMode.SUCCESS);
    }

    /** 空内容（配置被删除）不改变任何值。 */
    @Test
    void blankContentShouldBeIgnored() {
        PayoutRuntimeConfig runtimeConfig = defaultConfig();
        NacosConfigRefresher refresher = new NacosConfigRefresher(runtimeConfig);

        refresher.applyContent("");
        refresher.applyContent(null);

        assertThat(runtimeConfig.maxAttempts()).isEqualTo(3);
        assertThat(runtimeConfig.gatewayMode()).isEqualTo(ChannelMode.SUCCESS);
    }
}
