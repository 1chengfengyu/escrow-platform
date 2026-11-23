package com.escrow.platform.ai.infrastructure.llm;

import com.escrow.platform.ai.domain.AiPhase;
import com.escrow.platform.ai.domain.LlmGateway;
import com.escrow.platform.ai.domain.LlmRequest;
import com.escrow.platform.ai.domain.LlmResult;

/**
 * 离线桩（V10-c1）：无 key 时的默认实现。
 *
 * <p><b>它不是"假的 AI 数字"</b>：stub 模式下业务链路根本不调用本类，
 * 槽位走规则解析器、重排走确定性策略、理由走模板。本类只用于网关自身的单测
 * （缓存、预算、降级、成本归因），因此它返回固定文本，不参与任何评测指标。</p>
 *
 * @since V10
 */
public class StubLlmGateway implements LlmGateway {

    @Override
    public LlmResult complete(LlmRequest request) {
        String text = switch (request.phase()) {
            case UNDERSTAND -> "{\"skill\":null,\"priceMax\":null,\"location\":null,\"weekend\":null}";
            case RERANK -> "{\"order\":[]}";
            case GENERATE -> "";
            case FILTER, RECALL -> "";
        };
        return new LlmResult(text, "stub", estimate(request.user()), estimate(text), false);
    }

    private int estimate(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }

    /** 供测试断言阶段是否命中。 */
    public static boolean isModelPhase(AiPhase phase) {
        return phase == AiPhase.UNDERSTAND || phase == AiPhase.RERANK || phase == AiPhase.GENERATE;
    }
}
