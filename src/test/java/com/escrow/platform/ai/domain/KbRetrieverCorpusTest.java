package com.escrow.platform.ai.domain;

import com.escrow.platform.ai.infrastructure.kb.AiKbCorpus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实语料检索测试（V10-c5）：防止「单测用桩语料全绿、线上真实语料召回归零」。
 *
 * <p>语料按 UTF-8 随包发布，检索用二元切分；这条用例直接读
 * {@code src/main/resources/ai/kb/corpus.jsonl}，不经过任何 mock。</p>
 *
 * @since V10
 */
class KbRetrieverCorpusTest {

    private final KbRetriever retriever = new KbRetriever();

    private List<KbChunk> corpus() throws IOException {
        return AiKbCorpus.load(new ClassPathResource("ai/kb/corpus.jsonl"), new ObjectMapper()).chunks();
    }

    /** 托管与打款类问题必须命中对应条款。 */
    @Test
    void shouldRetrieveEscrowRuleForRealQuestion() throws IOException {
        List<KbChunk> hits = retriever.retrieve("托管资金什么时候打给服务者？", corpus(), 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).citationId()).isIn("KB-ESCROW-01", "KB-PAYOUT-01");
    }

    /** 纯业务无关问题不得硬凑引用。 */
    @Test
    void shouldReturnNothingForIrrelevantQuestion() throws IOException {
        assertThat(retriever.retrieve("今天天气怎么样", corpus(), 3)).isEmpty();
    }
}
