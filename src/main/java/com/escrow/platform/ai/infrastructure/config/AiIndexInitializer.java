package com.escrow.platform.ai.infrastructure.config;

import com.escrow.platform.ai.application.AiIndexService;
import com.escrow.platform.ai.infrastructure.kb.AiKbCorpus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时把当前知识库语料登记为一个索引版本（V10-c5）。
 *
 * <p>没有这一步，{@code ai_index_version} 永远是空表：版本号只存在于代码里，
 * 回放与排障时无法回答"这条答案当时用的是哪一版语料"。启动时登记一次，
 * 语料内容变化就把 {@link #KB_INDEX_VERSION} 递增。</p>
 *
 * <p>登记失败不阻断启动：索引记录是可观测数据，问答本身只依赖 classpath 语料
 * 与 {@code ai_index_stale_ref}，缺版本行不影响答题。</p>
 *
 * @since V10
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiIndexInitializer implements ApplicationRunner {

    /** 语料版本号：与 prompt 版本同理，内容变了必须递增，否则两批答案不可比。 */
    public static final int KB_INDEX_VERSION = 1;

    private final AiIndexService indexService;
    private final AiKbCorpus corpus;

    @Override
    public void run(ApplicationArguments args) {
        int chunkCount = corpus.chunks().size();
        if (chunkCount == 0) {
            return;
        }
        try {
            indexService.registerBuilding(KB_INDEX_VERSION, chunkCount);
            indexService.activate(KB_INDEX_VERSION, chunkCount);
            log.info("知识索引已激活 version={} chunks={}", KB_INDEX_VERSION, chunkCount);
        } catch (RuntimeException ex) {
            log.warn("知识索引登记失败，问答不受影响 version={}", KB_INDEX_VERSION, ex);
        }
    }
}
