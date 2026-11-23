package com.escrow.platform.ai.application;

import com.escrow.platform.ai.domain.AiUnavailableException;
import com.escrow.platform.ai.domain.CandidateScorer;
import com.escrow.platform.ai.domain.DeviationGuard;
import com.escrow.platform.ai.domain.QuerySlots;
import com.escrow.platform.ai.domain.ReasonTemplate;
import com.escrow.platform.ai.domain.RuleRerankPolicy;
import com.escrow.platform.ai.infrastructure.config.AiProperties;
import com.escrow.platform.ai.infrastructure.entity.AiWorkerProfileEntity;
import com.escrow.platform.ai.infrastructure.llm.LlmGatewayRouter;
import com.escrow.platform.ai.infrastructure.mapper.AiWorkerProfileMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 检索链路（V10-c4/c7）：权重基线、草稿过期、灰度重排降级。
 *
 * @since V10
 */
@ExtendWith(MockitoExtension.class)
class AiSearchServiceTest {

    private static final LoginUser EMPLOYER =
            new LoginUser(2001L, "employer@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);

    @Mock
    private AiWorkerProfileMapper profileMapper;
    @Mock
    private LlmGatewayRouter router;
    @Mock
    private AiTraceService traceService;

    private AiProperties properties;
    private AiDraftStore draftStore;
    private AiSearchService service;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        draftStore = new AiDraftStore(300);
        service = new AiSearchService(profileMapper, draftStore, new CandidateScorer(),
                new RuleRerankPolicy(), new DeviationGuard(), new ReasonTemplate(), router, properties,
                new AiRankResolver(properties), traceService, new ObjectMapper());
        lenient().when(profileMapper.selectList(any())).thenReturn(List.of(
                row(101L, "短视频剪辑,拍摄", 45000L, "北京", true, 40, 470),
                row(102L, "文案写作", 20000L, "北京", true, 30, 460),
                row(103L, "视频剪辑", 60000L, "上海", true, 55, 475)));
        lenient().when(traceService.record(anyLong(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn("t-1");
    }

    /** WEIGHTED 基线：命中技能者在前，理由三段可核。 */
    @Test
    void v10c4WeightedSearchReturnsReasons() {
        String draftId = draftStore.save(slots(), "找个会剪短视频的，北京，预算 500 以内");

        AiResults.Search result = service.search(EMPLOYER, draftId, List.of());

        assertThat(result.strategy()).isEqualTo("WEIGHTED");
        assertThat(result.rerankApplied()).isFalse();
        assertThat(result.items()).hasSizeLessThanOrEqualTo(5);
        assertThat(result.items().get(0).userId()).isEqualTo(101L);
        assertThat(result.items().get(0).reason()).contains("技能匹配");
        assertThat(result.items().get(0).evidenceFields()).contains("skills", "ratingX100");
        assertThat(result.traceNo()).isNotBlank();
    }

    /** 用户在回显标签里删掉预算与城市后，过滤条件必须随之放宽。 */
    @Test
    void v10c4RemovedTagsRelaxFilters() {
        String draftId = draftStore.save(slots(), "找个会剪短视频的，北京，预算 500 以内");

        AiResults.Search result = service.search(EMPLOYER, draftId, List.of("priceMax", "location"));

        assertThat(result.items()).extracting(AiResults.SearchItem::userId)
                .contains(103L);
    }

    /** 草稿不存在或过期：转 404，让用户重新解析。 */
    @Test
    void v10c4UnknownDraftRejected() {
        assertThatThrownBy(() -> service.search(EMPLOYER, "not-exists", List.of()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 灰度到 RERANK 但模型不可用：回退确定性重排，请求仍然成功。 */
    @Test
    void v10c7RerankFallsBackWhenModelUnavailable() {
        properties.setMode("live");
        properties.setApiKey("test-key");
        properties.getRank().setGrayRatio(10000);
        when(router.complete(any())).thenThrow(new AiUnavailableException("模型不可用"));
        String draftId = draftStore.save(slots(), "找个会剪短视频的");

        AiResults.Search result = service.search(EMPLOYER, draftId, List.of());

        assertThat(result.strategy()).isEqualTo("RERANK");
        assertThat(result.rerankApplied()).isTrue();
        assertThat(result.rerankNote()).contains("确定性重排");
        assertThat(result.items()).isNotEmpty();
    }

    private QuerySlots slots() {
        return new QuerySlots("短视频剪辑", 50000L, "北京", true, null, List.of(), 80);
    }

    private AiWorkerProfileEntity row(long userId, String skills, long price, String location,
                                      boolean weekend, int orders, int rating) {
        AiWorkerProfileEntity entity = new AiWorkerProfileEntity();
        entity.setUserId(userId);
        entity.setSkills(skills);
        entity.setExpectedUnitPrice(price);
        entity.setWorkLocation(location);
        entity.setAvailableWeekend(weekend);
        entity.setCompletedOrders(orders);
        entity.setRatingX100(rating);
        return entity;
    }
}
