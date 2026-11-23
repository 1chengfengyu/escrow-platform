package com.escrow.platform.ai.application;

import com.escrow.platform.ai.domain.AiGrayRouter;
import com.escrow.platform.ai.domain.AiRankStrategy;
import com.escrow.platform.ai.infrastructure.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 灰度策略解析器（V10-c7）：把配置与用户ID解析成本次请求实际使用的策略。
 *
 * <p>解析只做一件事，且只有一处：{\@code strategy} 写错时 {@link AiRankStrategy#parse}
 * 直接抛错而不是静默回退——配置事故必须暴露在启动/首个请求上。</p>
 *
 * @since V10
 */
@Component
@RequiredArgsConstructor
public class AiRankResolver {

    private final AiProperties properties;

    /** 本次请求的排序策略。 */
    public AiRankStrategy resolve(long userId) {
        AiRankStrategy baseline = AiRankStrategy.parse(properties.getRank().getStrategy());
        return AiGrayRouter.resolve(baseline, properties.getRank().getGrayRatio(), userId);
    }

    /** 本次请求的哈希桶号，写 trace 用。 */
    public int bucket(long userId) {
        return AiGrayRouter.bucket(userId);
    }
}
