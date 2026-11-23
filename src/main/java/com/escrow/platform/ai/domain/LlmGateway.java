package com.escrow.platform.ai.domain;

/**
 * 模型调用端口（V10-c1）。
 *
 * <p>放在 domain 而不是 infrastructure：上层只依赖这个接口，stub 与真实模型是两种实现，
 * 这样"无 key 全绿"与"有 key 可跑"共用同一条业务链路，评测才有意义。</p>
 *
 * @since V10
 */
public interface LlmGateway {

    /**
     * 同步调用一次。
     *
     * @throws AiUnavailableException 网络失败、限额耗尽或供应商返回错误
     */
    LlmResult complete(LlmRequest request);
}
