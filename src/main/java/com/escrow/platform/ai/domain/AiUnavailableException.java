package com.escrow.platform.ai.domain;

/**
 * 模型不可用（V10-c2 降级链的触发点）。
 *
 * <p>业务层捕获它之后不是报错，而是切到确定性路径（规则解析 / 规则重排 / 模板理由），
 * 并在 trace 里标记 {@code degraded=true}。这就是"token 预算超限自动降级"的实现方式。</p>
 *
 * @since V10
 */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message) {
        super(message);
    }

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
