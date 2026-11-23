package com.escrow.platform.ai.domain;

/**
 * 模型调用结果（V10-c1）：带 token 与成本，可归因到阶段。
 *
 * @param text             模型输出（可能是 JSON 字符串）
 * @param model            实际使用的模型
 * @param promptTokens     输入 token
 * @param completionTokens 输出 token
 * @param fallbackUsed     是否降级到备用模型
 * @since V10
 */
public record LlmResult(String text, String model, int promptTokens, int completionTokens, boolean fallbackUsed) {

    /** 总 token。 */
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
