package com.escrow.platform.dispute.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.dispute.domain.Dispute;
import com.escrow.platform.dispute.infrastructure.mapper.DisputeMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DisputeQueryService} 的 Mockito 单测（FR-TASK.DETAIL.02 R2 争议中）。
 *
 * <p>纯单测没有 MyBatis 启动过程，需手工初始化 {@code Dispute} 的 {@code TableInfo}
 * 缓存，否则 {@code LambdaQueryWrapper} 取不到列名。</p>
 *
 * @since V7
 */
@ExtendWith(MockitoExtension.class)
class DisputeQueryServiceTest {

    @Mock
    private DisputeMapper disputeMapper;

    private DisputeQueryService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Dispute.class);
    }

    @BeforeEach
    void setUp() {
        service = new DisputeQueryService(disputeMapper);
    }

    /** 名额集合为空：直接返回 0，不查库。 */
    @Test
    void countOpenShouldReturnZeroWithoutQueryWhenSlotsEmpty() {
        assertThat(service.countOpen(List.of())).isZero();
        assertThat(service.countOpen(null)).isZero();
        verifyNoInteractions(disputeMapper);
    }

    /** 有待裁决争议：透传计数。 */
    @Test
    void countOpenShouldReturnMapperCount() {
        when(disputeMapper.selectCount(any())).thenReturn(3L);

        assertThat(service.countOpen(List.of(1L, 2L))).isEqualTo(3);
        verify(disputeMapper).selectCount(any(LambdaQueryWrapper.class));
    }

    /** Mapper 返回 null：按 0 处理，不抛空指针。 */
    @Test
    void countOpenShouldTreatNullCountAsZero() {
        when(disputeMapper.selectCount(any())).thenReturn(null);

        assertThat(service.countOpen(List.of(1L))).isZero();
    }
}
