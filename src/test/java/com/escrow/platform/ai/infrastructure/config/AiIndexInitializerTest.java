package com.escrow.platform.ai.infrastructure.config;

import com.escrow.platform.ai.application.AiIndexService;
import com.escrow.platform.ai.domain.KbChunk;
import com.escrow.platform.ai.infrastructure.kb.AiKbCorpus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 索引版本登记（V10-c5）：启动时把语料登记为版本 {@link AiIndexInitializer#KB_INDEX_VERSION}。
 *
 * @since V10
 */
@ExtendWith(MockitoExtension.class)
class AiIndexInitializerTest {

    @Mock
    private AiIndexService indexService;
    @Mock
    private AiKbCorpus corpus;
    @InjectMocks
    private AiIndexInitializer initializer;

    /** 正常启动：先登记 BUILDING 再激活，条数与语料一致。 */
    @Test
    void v10c5RunRegistersAndActivatesCurrentCorpus() {
        when(corpus.chunks()).thenReturn(List.of(
                new KbChunk("KB-ESCROW-01", "托管", "托管规则", "SRS"),
                new KbChunk("KB-PAYOUT-01", "打款", "打款规则", "SRS")));

        initializer.run(null);

        verify(indexService).registerBuilding(AiIndexInitializer.KB_INDEX_VERSION, 2);
        verify(indexService).activate(AiIndexInitializer.KB_INDEX_VERSION, 2);
    }

    /** 空语料：不登记，避免写出 chunk_count=0 的假版本。 */
    @Test
    void v10c5EmptyCorpusSkipsRegistration() {
        when(corpus.chunks()).thenReturn(List.of());

        initializer.run(null);

        verify(indexService, never()).registerBuilding(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
        verify(indexService, never()).activate(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    /** 登记失败不阻断启动：索引记录属于可观测数据。 */
    @Test
    void v10c5RegistrationFailureDoesNotBreakStartup() {
        when(corpus.chunks()).thenReturn(List.of(new KbChunk("KB-1", "t", "x", "SRS")));
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(indexService).registerBuilding(org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt());

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();
    }
}
