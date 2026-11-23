package com.escrow.platform.ai.domain;

/**
 * 模型调用请求（V10-c1）：只带 prompt 与预算，不带任何用户身份。
 *
 * <p><b>工具参数不信任模型</b>：能查谁由服务端上下文决定，模型只决定"调哪个工具"。
 * 因此请求体里没有 userId 字段——这一条是防越权的结构性保证，不靠调用方自觉。</p>
 *
 * @param promptVersion 版本号，进缓存键，也进日志
 * @param phase         所属阶段，用于预算与成本归因
 * @param system        系统指令
 * @param user          已清洗、已隔离的用户内容
 * @param maxTokens     本次允许的最大输出 token
 * @param model         目标模型；空表示用配置里的主模型
 * @since V10
 */
public record LlmRequest(String promptVersion, AiPhase phase, String system, String user,
                         int maxTokens, String model) {

    /** 用主模型。 */
    public static LlmRequest of(String promptVersion, AiPhase phase, String system, String user, int maxTokens) {
        return new LlmRequest(promptVersion, phase, system, user, maxTokens, null);
    }
}
