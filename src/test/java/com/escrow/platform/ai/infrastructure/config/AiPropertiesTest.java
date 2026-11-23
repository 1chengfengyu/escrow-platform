package com.escrow.platform.ai.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code escrow.ai.*} 的配置契约（V10，2026-11-20 修复）。
 *
 * <p>锁两件事：<b>默认接入点不能是 OpenAI 域名</b>（那台域名在本机不可达，
 * 默认值一错就是"每次检索白等 10 秒"），以及 <b>live 模式配置不全必须启动失败</b>
 * （静默退化成桩会让人以为模型在跑）。</p>
 *
 * @since V10
 */
class AiPropertiesTest {

    /** 默认值必须是当前实际可用的供应商，否则 stub→live 一改就退化。 */
    @Test
    void v10c1DefaultsPointToReachableProvider() {
        AiProperties properties = new AiProperties();

        assertThat(properties.getBaseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(properties.getModel()).isEqualTo("deepseek-chat");
        assertThat(properties.getBaseUrl()).doesNotContain("api.openai.com");
    }

    /** 默认 stub：不配任何环境变量就该能启动、能跑全量测试。 */
    @Test
    void v10c1StubModeNeedsNoKeys() {
        AiProperties properties = new AiProperties();

        assertThat(properties.live()).isFalse();
        assertThatCode(properties::validateForLive).doesNotThrowAnyException();
    }

    /** live 却没 key：这是配置错误，必须启动期失败，不能悄悄退化成桩。 */
    @Test
    void v10c1LiveWithoutKeyFailsFast() {
        AiProperties properties = new AiProperties();
        properties.setMode("live");

        assertThatThrownBy(properties::validateForLive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
    }

    /** live 但接入点或模型为空：同样启动即失败，不留到第一次检索。 */
    @Test
    void v10c1LiveWithoutEndpointOrModelFailsFast() {
        AiProperties noEndpoint = liveProperties();
        noEndpoint.setBaseUrl(" ");
        assertThatThrownBy(noEndpoint::validateForLive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");

        AiProperties noModel = liveProperties();
        noModel.setModel("");
        assertThatThrownBy(noModel::validateForLive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model");
    }

    /** 主模型 + 备模型去重后按顺序尝试。 */
    @Test
    void v10c1ModelChainDeduplicates() {
        AiProperties properties = liveProperties();
        properties.setFallbackModels(" deepseek-chat , deepseek-reasoner ,, ");

        assertThat(properties.modelChain()).containsExactly("deepseek-chat", "deepseek-reasoner");
    }

    private AiProperties liveProperties() {
        AiProperties properties = new AiProperties();
        properties.setMode("live");
        properties.setApiKey("test-key");
        return properties;
    }
}
