package com.escrow.platform.ai.infrastructure.llm;

import com.escrow.platform.ai.domain.LlmGateway;
import com.escrow.platform.ai.domain.LlmRequest;
import com.escrow.platform.ai.domain.LlmResult;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实模型客户端（V10-c1）：手工构建 Spring AI 的 {@link OpenAiChatModel}。
 *
 * <p><b>为什么不用 starter 自动配置</b>：{@code spring-ai-starter-model-openai} 在缺
 * api-key 时会让应用启动失败，而本项目要求"无 key 也能启动、也能跑全量测试"。
 * 手工构建 + 只在 {@code mode=live} 时创建，才不会把测试环境拖下水。</p>
 *
 * <p>OpenAI 兼容协议意味着换供应商只需改 {@code base-url} 与模型名，
 * 不用改代码——这是选 OpenAI 协议做统一入口的理由。</p>
 *
 * @since V10
 */
public class OpenAiLlmGateway implements LlmGateway {

    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;
    private final Map<String, OpenAiChatModel> models = new ConcurrentHashMap<>();

    /**
     * @param baseUrl      OpenAI 兼容端点（DeepSeek 亦可）
     * @param apiKey       供应商密钥
     * @param defaultModel 请求未指定模型时用它；由装配层从 {@code escrow.ai.model} 传入，
     *                     避免在本类里再写一个"第二默认值"——两处默认值迟早会不一致
     */
    public OpenAiLlmGateway(String baseUrl, String apiKey, String defaultModel) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
    }

    @Override
    public LlmResult complete(LlmRequest request) {
        String modelName = resolveModel(request);
        OpenAiChatModel model = models.computeIfAbsent(modelName, this::build);
        try {
            ChatResponse response = model.call(new Prompt(
                    List.of(new SystemMessage(request.system()), new UserMessage(request.user())),
                    OpenAiChatOptions.builder().model(modelName).maxTokens(request.maxTokens()).build()));
            String text = response.getResult().getOutput().getText();
            int promptTokens = estimateTokens(request.system() + request.user());
            int completionTokens = estimateTokens(text);
            return new LlmResult(text == null ? "" : text, modelName, promptTokens, completionTokens, false);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("模型调用失败 model=" + modelName, ex);
        }
    }

    private String resolveModel(LlmRequest request) {
        return request.model() == null || request.model().isBlank() ? defaultModel : request.model();
    }

    private OpenAiChatModel build(String modelName) {
        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(modelName).temperature(0.0).build())
                .build();
    }

    /**
     * token 估算：按 4 字符 1 token 粗算。
     *
     * <p>真实用量应由供应商回包给，但 OpenAI 兼容实现回包字段不一致；
     * 预算控制只需要量级准确，因此统一用估算，避免解析差异导致归因中断。</p>
     */
    private int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }
}
