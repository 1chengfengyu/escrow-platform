package com.escrow.platform.ai.infrastructure.kb;

import com.escrow.platform.ai.domain.KbChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则知识库语料（V10-c5）：产品事实的唯一来源，随包发布。
 *
 * <p>语料放 {@code src/main/resources/ai/kb/corpus.jsonl}——它是产品内容而不是测试数据，
 * 线上问答直接用它。测试语料（query-set / relevance-set 等）才放 {@code src/test/resources}，
 * 两者不能混。</p>
 *
 * @since V10
 */
public final class AiKbCorpus {

    private final List<KbChunk> chunks;

    private AiKbCorpus(List<KbChunk> chunks) {
        this.chunks = List.copyOf(chunks);
    }

    /**
     * 从 classpath 资源加载。
     *
     * <p>加载失败直接抛错：知识库为空时所有问答都会弃答，这是启动期就该暴露的问题，
     * 不能拖到用户提问才发现。</p>
     */
    public static AiKbCorpus load(Resource resource, ObjectMapper objectMapper) throws IOException {
        List<KbChunk> chunks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                chunks.add(objectMapper.readValue(line, KbChunk.class));
            }
        }
        if (chunks.isEmpty()) {
            throw new IllegalStateException("知识库语料为空：" + resource.getDescription());
        }
        return new AiKbCorpus(chunks);
    }

    /** 供测试直接构造。 */
    public static AiKbCorpus of(List<KbChunk> chunks) {
        return new AiKbCorpus(chunks);
    }

    public List<KbChunk> chunks() {
        return chunks;
    }
}
