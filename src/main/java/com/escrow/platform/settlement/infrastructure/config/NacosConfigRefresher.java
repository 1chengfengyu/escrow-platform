package com.escrow.platform.settlement.infrastructure.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.escrow.platform.settlement.application.PayoutRuntimeConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * Nacos 配置中心接入（V5，CR-13）：改配置不重启。
 *
 * <p><b>只接配置中心</b>：单体阶段不需要注册发现，因此不引 Spring Cloud，
 * 直接用裸 {@code nacos-client} 拉取 {@code escrow-payout.properties} 并注册监听。</p>
 *
 * <p><b>失败降级</b>：Nacos 不可达时只记一条 error，应用按 {@code application.yml}
 * 的本地值继续跑——配置中心是增强项，不能成为启动的单点。</p>
 *
 * <p><b>非法值不落地</b>：{@link PayoutRuntimeConfig#apply(Map)} 抛异常时保留旧值，
 * 避免一次手误把在途打款推进错误分支。</p>
 *
 * @since V5
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "escrow.nacos.enabled", havingValue = "true")
public class NacosConfigRefresher implements InitializingBean, DisposableBean {

    private final PayoutRuntimeConfig runtimeConfig;

    @Value("${escrow.nacos.server-addr:127.0.0.1:8848}")
    private String serverAddr;

    @Value("${escrow.nacos.namespace:}")
    private String namespace;

    @Value("${escrow.nacos.data-id:escrow-payout.properties}")
    private String dataId;

    @Value("${escrow.nacos.group:DEFAULT_GROUP}")
    private String group;

    @Value("${escrow.nacos.timeout-ms:3000}")
    private long timeoutMs;

    private ConfigService configService;

    @Override
    public void afterPropertiesSet() {
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, serverAddr);
        if (StringUtils.hasText(namespace)) {
            properties.put(PropertyKeyConst.NAMESPACE, namespace);
        }
        try {
            configService = NacosFactory.createConfigService(properties);
            String content = configService.getConfig(dataId, group, timeoutMs);
            applyContent(content);
            configService.addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    applyContent(configInfo);
                }
            });
            log.info("Nacos 配置中心已接入 server={} dataId={} group={}", serverAddr, dataId, group);
        } catch (Exception ex) {
            log.error("Nacos 配置中心不可用，降级为本地配置 server={} dataId={}",
                    serverAddr, dataId, ex);
            shutdownQuietly();
            configService = null;
        }
    }

    /**
     * 应用一段 properties 文本；空内容或非法值只记日志。
     *
     * @param content Nacos 配置内容（{@code key=value} 按行）
     */
    void applyContent(String content) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        Properties parsed = new Properties();
        try {
            parsed.load(new StringReader(content));
        } catch (IOException ex) {
            log.warn("Nacos 配置解析失败，保留旧值 dataId={}", dataId, ex);
            return;
        }
        Map<String, String> values = new HashMap<>();
        parsed.stringPropertyNames().forEach(key -> values.put(key, parsed.getProperty(key)));
        try {
            runtimeConfig.apply(values);
            log.info("打款运行期配置已刷新 keys={}", values.keySet());
        } catch (RuntimeException ex) {
            log.warn("Nacos 配置非法，保留旧值 keys={}", values.keySet(), ex);
        }
    }

    @Override
    public void destroy() {
        shutdownQuietly();
    }

    private void shutdownQuietly() {
        if (configService == null) {
            return;
        }
        try {
            configService.shutDown();
        } catch (Exception ex) {
            log.warn("关闭 Nacos 配置客户端失败", ex);
        }
    }
}
