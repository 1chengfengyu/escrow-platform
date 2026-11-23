package com.escrow.platform.ai.application;

import com.escrow.platform.ai.domain.AiPhase;
import com.escrow.platform.ai.domain.AiRankStrategy;
import com.escrow.platform.ai.domain.TraceStage;
import com.escrow.platform.ai.infrastructure.entity.AiTraceEntity;
import com.escrow.platform.ai.infrastructure.mapper.AiTraceMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 链路 trace（V10-c6）：复用 MDC traceId、阶段落库、重复键不失败、回放 404。
 *
 * @since V10
 */
@ExtendWith(MockitoExtension.class)
class AiTraceServiceTest {

    @Mock
    private AiTraceMapper traceMapper;

    private AiTraceService service;

    @BeforeEach
    void setUp() {
        service = new AiTraceService(traceMapper, new ObjectMapper());
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-abc-001");
    }

    @AfterEach
    void tearDown() {
        MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
    }

    /** 记录时 traceNo 必须等于 MDC 里的 traceId，且阶段 JSON 带上明细。 */
    @Test
    void v10c6RecordUsesMdcTraceId() {
        String traceNo = service.record(2001L, "找个会剪短视频的", AiRankStrategy.WEIGHTED, 8109, 2,
                List.of(new TraceStage(AiPhase.RERANK, 12L, 30, false, "规则重排",
                        List.of(new TraceStage.TraceCall("stub", 30, 45L, false, false)))));

        ArgumentCaptor<AiTraceEntity> captor = ArgumentCaptor.forClass(AiTraceEntity.class);
        verify(traceMapper).insert(captor.capture());
        AiTraceEntity saved = captor.getValue();
        assertThat(traceNo).isEqualTo("trace-abc-001");
        assertThat(saved.getTraceNo()).isEqualTo("trace-abc-001");
        assertThat(saved.getTotalTokens()).isEqualTo(30);
        assertThat(saved.getCostMicros()).isEqualTo(45L);
        assertThat(saved.getStagesJson()).contains("RERANK").contains("规则重排");
    }

    /** 重复 traceId：不覆盖、不抛出，主流程照常返回。 */
    @Test
    void v10c6DuplicateTraceIsSwallowed() {
        when(traceMapper.insert(any(AiTraceEntity.class))).thenThrow(new DuplicateKeyException("dup"));

        assertThatCode(() -> service.record(2001L, "q", AiRankStrategy.WEIGHTED, 1, 0, List.of()))
                .doesNotThrowAnyException();
    }

    /** 回放不存在的链路：404。 */
    @Test
    void v10c6ReplayMissingTraceIsNotFound() {
        when(traceMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.replay("missing", 2001L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 别人的链路：403，且不回传查询词。 */
    @Test
    void v10c6ReplayForeignTraceIsForbidden() {
        AiTraceEntity entity = new AiTraceEntity();
        entity.setTraceNo("t-2");
        entity.setUserId(2001L);
        entity.setQueryText("别人的搜索词");
        when(traceMapper.selectOne(any())).thenReturn(entity);

        assertThatThrownBy(() -> service.replay("t-2", 3001L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    /** 正常回放：阶段数组可反序列化成对象。 */
    @Test
    void v10c6ReplayParsesStages() {
        AiTraceEntity entity = new AiTraceEntity();
        entity.setTraceNo("t-1");
        entity.setUserId(2001L);
        entity.setQueryText("q");
        entity.setStrategy("WEIGHTED");
        entity.setBucket(1);
        entity.setResultCount(1);
        entity.setTotalTokens(10);
        entity.setCostMicros(15L);
        entity.setStagesJson("[{\"phase\":\"FILTER\",\"ms\":3,\"tokens\":0,\"degraded\":false,"
                + "\"note\":\"召回 1 条\",\"calls\":[]}]");
        entity.setCreatedAt(LocalDateTime.now());
        when(traceMapper.selectOne(any())).thenReturn(entity);

        AiResults.Trace trace = service.replay("t-1", 2001L);

        assertThat(trace.stages()).hasSize(1);
        assertThat(trace.stages().get(0).phase()).isEqualTo(AiPhase.FILTER);
        assertThat(trace.stages().get(0).note()).isEqualTo("召回 1 条");
    }
}
