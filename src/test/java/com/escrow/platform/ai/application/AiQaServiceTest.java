package com.escrow.platform.ai.application;

import com.escrow.platform.ai.domain.AnswerComposer;
import com.escrow.platform.ai.domain.InjectionGuard;
import com.escrow.platform.ai.domain.KbChunk;
import com.escrow.platform.ai.domain.KbRetriever;
import com.escrow.platform.ai.domain.LlmResult;
import com.escrow.platform.ai.infrastructure.config.AiProperties;
import com.escrow.platform.ai.infrastructure.kb.AiKbCorpus;
import com.escrow.platform.ai.infrastructure.llm.LlmGatewayRouter;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 规则问答（V10-c5）：命中即带引用、无依据即弃答、空问题拒绝。
 *
 * @since V10
 */
@ExtendWith(MockitoExtension.class)
class AiQaServiceTest {

    private static final LoginUser WORKER =
            new LoginUser(3001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Mock
    private AiIndexService indexService;
    @Mock
    private LlmGatewayRouter router;
    @Mock
    private AiTraceService traceService;

    private AiQaService service;
    private AiProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        AiKbCorpus corpus = AiKbCorpus.of(List.of(
                new KbChunk("KB-PAYOUT-01", "打款时效",
                        "验收通过后，平台在一个工作日内完成打款。遇节假日顺延，打款状态可在收支明细中查询。", "test"),
                new KbChunk("KB-ACCEPT-01", "验收时限",
                        "雇主应在服务者提交交付物后七十二小时内完成验收。逾期未验收，系统自动验收通过。", "test")));
        service = new AiQaService(new KbRetriever(), new AnswerComposer(), corpus, indexService,
                new InjectionGuard(), router, properties, new AiRankResolver(properties), traceService);
        lenient().when(indexService.isStale(anyString())).thenReturn(false);
        lenient().when(traceService.record(anyLong(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn("t-1");
    }

    /** 命中知识库：答案来自原文，引用就是命中条目。 */
    @Test
    void v10c5AnswerCarriesCitation() {
        AiResults.Qa result = service.ask(WORKER, "验收通过后多久打款");

        assertThat(result.abstain()).isFalse();
        assertThat(result.answer()).contains("打款");
        assertThat(result.citations()).contains("KB-PAYOUT-01");
        assertThat(result.traceNo()).isNotBlank();
    }

    /** 缓存命中（tokens=0）时仍必须采用润色答案：0 token ≠ 没有结果。 */
    @Test
    void v10c5CacheHitShouldStillUsePolishedAnswer() {
        properties.setMode("live");
        properties.setApiKey("test-key");
        when(router.complete(any())).thenReturn(new LlmResult(
                "验收通过后，平台在一个工作日内完成打款。", "cache", 0, 0, false));

        AiResults.Qa result = service.ask(WORKER, "验收通过后多久打款");

        assertThat(result.answer()).isEqualTo("验收通过后，平台在一个工作日内完成打款。");
        assertThat(result.degraded()).isFalse();
        assertThat(result.citations()).contains("KB-PAYOUT-01");
    }

    /** 知识库无命中：弃答，不编造。 */
    @Test
    void v10c5UnknownQuestionAbstains() {
        AiResults.Qa result = service.ask(WORKER, "今天天气怎么样");

        assertThat(result.abstain()).isTrue();
        assertThat(result.citations()).isEmpty();
        assertThat(result.answer()).contains("知识库暂未收录");
    }

    /** 空问题：参数错误，不落 trace。 */
    @Test
    void v10c5BlankQuestionRejected() {
        assertThatThrownBy(() -> service.ask(WORKER, " "))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PARAMETER));
    }
}
