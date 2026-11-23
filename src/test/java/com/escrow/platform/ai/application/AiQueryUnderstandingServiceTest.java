package com.escrow.platform.ai.application;

import com.escrow.platform.ai.domain.AiUnavailableException;
import com.escrow.platform.ai.domain.InjectionGuard;
import com.escrow.platform.ai.domain.SlotParser;
import com.escrow.platform.ai.infrastructure.config.AiProperties;
import com.escrow.platform.ai.infrastructure.llm.LlmGatewayRouter;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 查询理解（V10-c2）：规则路径、空输入、模型失败降级。
 *
 * @since V10
 */
@ExtendWith(MockitoExtension.class)
class AiQueryUnderstandingServiceTest {

    private static final LoginUser EMPLOYER =
            new LoginUser(2001L, "employer@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);

    @Mock
    private LlmGatewayRouter router;
    @Mock
    private AiTraceService traceService;

    private AiProperties properties;
    private AiQueryUnderstandingService service;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        service = new AiQueryUnderstandingService(properties, router, new SlotParser(), new InjectionGuard(),
                new AiDraftStore(300), traceService, new AiRankResolver(properties), new ObjectMapper());
    }

    /** stub 模式：规则解析出槽位与可编辑标签，同时落一条 trace。 */
    @Test
    void v10c2StubModeParsesSlotsAndTags() {
        AiResults.Understand result = service.parse(EMPLOYER, "找个会剪短视频的，北京，预算 500 以内，周末能干活");

        assertThat(result.degraded()).isFalse();
        assertThat(result.slots().skill()).isEqualTo("短视频剪辑");
        assertThat(result.slots().priceMax()).isEqualTo(50000L);
        assertThat(result.slots().location()).isEqualTo("北京");
        assertThat(result.slots().weekend()).isTrue();
        assertThat(result.tags()).extracting(tag -> tag.key())
                .containsExactly("skill", "priceMax", "location", "weekend");
        assertThat(result.draftId()).isNotBlank();
        verify(traceService).record(any(Long.class), any(), any(), any(Integer.class), any(Integer.class), any());
    }

    /** 空问题直接拒绝，不产生草稿也不写 trace。 */
    @Test
    void v10c2BlankQueryRejected() {
        assertThatThrownBy(() -> service.parse(EMPLOYER, "   "))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PARAMETER));
    }

    /** live 模式模型不可用：回退规则解析并标记 degraded，用户仍可确认。 */
    @Test
    void v10c2ModelFailureFallsBackToRules() {
        properties.setMode("live");
        properties.setApiKey("test-key");
        when(router.complete(any())).thenThrow(new AiUnavailableException("模型不可用"));

        AiResults.Understand result = service.parse(EMPLOYER, "找个会剪短视频的，预算 500 以内");

        assertThat(result.degraded()).isTrue();
        assertThat(result.slots().skill()).isEqualTo("短视频剪辑");
        assertThat(result.slots().priceMax()).isEqualTo(50000L);
    }
}
