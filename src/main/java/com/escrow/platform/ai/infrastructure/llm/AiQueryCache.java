package com.escrow.platform.ai.infrastructure.llm;

import java.util.Optional;

/**
 * 查询缓存端口（V10-c1）：同一 prompt 版本 + 同一输入直接复用上次输出。
 *
 * <p>缓存键必须包含 prompt 版本：升版本即天然失效，避免改了 prompt 还吃旧结果。</p>
 *
 * @since V10
 */
public interface AiQueryCache {

    Optional<String> get(String key);

    void put(String key, String value, long ttlSeconds);

    void clear();
}
