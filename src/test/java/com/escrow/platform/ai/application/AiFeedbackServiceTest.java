package com.escrow.platform.ai.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.ai.infrastructure.entity.AiFeedbackEntity;
import com.escrow.platform.ai.infrastructure.entity.AiTraceEntity;
import com.escrow.platform.ai.infrastructure.mapper.AiFeedbackMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 反馈幂等（V10-c6/c7）：唯一键兜底、越权拒绝、60 秒窗口。
 *
 * @since V10
 */
@ExtendWith(MockitoExtension.class)
class AiFeedbackServiceTest {

    private static final LoginUser EMPLOYER =
            new LoginUser(2001L, "employer@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);

    @Mock
    private AiFeedbackMapper feedbackMapper;
    @Mock
    private AiTraceService traceService;

    private AiFeedbackService service;

    /** LambdaUpdateWrapper 需要实体元数据，纯单测里显式初始化。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AiFeedbackEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new AiFeedbackService(feedbackMapper, traceService);
    }

    /** 首次上报：插入一条，标记按请求写入。 */
    @Test
    void v10c6FirstReportInserts() {
        when(traceService.require("t-1")).thenReturn(trace(2001L, LocalDateTime.now()));
        when(feedbackMapper.selectOne(any())).thenReturn(null);

        service.record(EMPLOYER, "t-1", true, true);

        ArgumentCaptor<AiFeedbackEntity> captor = ArgumentCaptor.forClass(AiFeedbackEntity.class);
        verify(feedbackMapper).insert(captor.capture());
        assertThat(captor.getValue().getClicked()).isTrue();
        assertThat(captor.getValue().getSecondSearch()).isTrue();
    }

    /** 超 60 秒的二次搜索不记：那是新需求，不是"没搜到"。 */
    @Test
    void v10c6SecondSearchOutsideWindowIgnored() {
        when(traceService.require("t-1")).thenReturn(trace(2001L, LocalDateTime.now().minusMinutes(5)));
        when(feedbackMapper.selectOne(any())).thenReturn(null);

        service.record(EMPLOYER, "t-1", false, true);

        ArgumentCaptor<AiFeedbackEntity> captor = ArgumentCaptor.forClass(AiFeedbackEntity.class);
        verify(feedbackMapper).insert(captor.capture());
        assertThat(captor.getValue().getSecondSearch()).isFalse();
    }

    /** 重复上报：只置位、不累加，走更新而不是再插一条。 */
    @Test
    void v10c6RepeatReportMergesFlags() {
        AiFeedbackEntity existing = new AiFeedbackEntity();
        existing.setId(7L);
        existing.setClicked(false);
        existing.setSecondSearch(false);
        when(traceService.require("t-1")).thenReturn(trace(2001L, LocalDateTime.now()));
        when(feedbackMapper.selectOne(any())).thenReturn(existing);

        service.record(EMPLOYER, "t-1", true, false);

        verify(feedbackMapper, never()).insert(any(AiFeedbackEntity.class));
        verify(feedbackMapper).update(isNull(), any());
    }

    /** 只能上报自己的链路。 */
    @Test
    void v10c6OtherUserTraceForbidden() {
        when(traceService.require("t-1")).thenReturn(trace(9999L, LocalDateTime.now()));

        assertThatThrownBy(() -> service.record(EMPLOYER, "t-1", true, false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    private AiTraceEntity trace(long userId, LocalDateTime createdAt) {
        AiTraceEntity entity = new AiTraceEntity();
        entity.setTraceNo("t-1");
        entity.setUserId(userId);
        entity.setCreatedAt(createdAt);
        return entity;
    }
}
