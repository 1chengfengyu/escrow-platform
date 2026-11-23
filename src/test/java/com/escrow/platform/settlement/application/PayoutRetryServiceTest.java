package com.escrow.platform.settlement.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.domain.Settlement;
import com.escrow.platform.settlement.domain.SettlementStatus;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import com.escrow.platform.settlement.infrastructure.mapper.SettlementMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自动重试测试（v5-c3）：每次尝试换新请求号、序号递增、额度用尽不再建单。
 *
 * @since V5
 */
@ExtendWith(MockitoExtension.class)
class PayoutRetryServiceTest {

    @Mock
    private SettlementMapper settlementMapper;
    @Mock
    private PayoutOrderMapper payoutOrderMapper;
    @Mock
    private PayoutRuntimeConfig runtimeConfig;

    @InjectMocks
    private PayoutRetryService service;

    /** 纯单测没有 MyBatis 上下文，Lambda 列名解析需要显式初始化表元数据。 */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, Settlement.class);
        TableInfoHelper.initTableInfo(assistant, PayoutOrder.class);
    }

    private Settlement settlement(SettlementStatus status) {
        Settlement settlement = new Settlement();
        settlement.setId(50L);
        settlement.setNetAmount(9_700L);
        settlement.setStatus(status);
        return settlement;
    }

    private PayoutOrder lastAttempt(int seq) {
        PayoutOrder payout = new PayoutOrder();
        payout.setId(60L);
        payout.setSettlementId(50L);
        payout.setRequestNo("PO-OLD");
        payout.setAttemptSeq(seq);
        payout.setAmount(9_700L);
        return payout;
    }

    /** 第 2 次尝试：新单据、新请求号、序号 +1、结算回到打款中。 */
    @Test
    void shouldCreateNextAttemptWithNewRequestNo() {
        when(settlementMapper.selectById(50L))
                .thenReturn(settlement(SettlementStatus.SETTLEMENT_PENDING_RETRY));
        when(payoutOrderMapper.selectOne(any())).thenReturn(lastAttempt(1));
        when(runtimeConfig.maxAttempts()).thenReturn(3);
        when(payoutOrderMapper.insert(any(PayoutOrder.class))).thenAnswer(invocation -> {
            ((PayoutOrder) invocation.getArgument(0)).setId(77L);
            return 1;
        });
        when(settlementMapper.update(any(), any())).thenReturn(1);

        assertThat(service.startNextAttempt(50L)).isEqualTo(77L);

        ArgumentCaptor<PayoutOrder> captor = ArgumentCaptor.forClass(PayoutOrder.class);
        verify(payoutOrderMapper).insert(captor.capture());
        PayoutOrder created = captor.getValue();
        assertThat(created.getAttemptSeq()).isEqualTo(2);
        assertThat(created.getRequestNo()).startsWith("PO").isNotEqualTo("PO-OLD");
        assertThat(created.getAmount()).isEqualTo(9_700L);
        assertThat(created.getStatus()).isEqualTo(PayoutStatus.PAYOUT_PENDING_EXECUTE);
    }

    /** 额度用尽：不建新单。 */
    @Test
    void shouldNotCreateAttemptWhenLimitReached() {
        when(settlementMapper.selectById(50L))
                .thenReturn(settlement(SettlementStatus.SETTLEMENT_PENDING_RETRY));
        when(payoutOrderMapper.selectOne(any())).thenReturn(lastAttempt(3));
        when(runtimeConfig.maxAttempts()).thenReturn(3);

        assertThat(service.startNextAttempt(50L)).isNull();

        verify(payoutOrderMapper, never()).insert(any(PayoutOrder.class));
    }

    /** 结算单不在待重试：不建新单。 */
    @Test
    void shouldNotCreateAttemptWhenSettlementNotRetryable() {
        when(settlementMapper.selectById(50L))
                .thenReturn(settlement(SettlementStatus.SETTLEMENT_COMPLETED));

        assertThat(service.startNextAttempt(50L)).isNull();

        verify(payoutOrderMapper, never()).insert(any(PayoutOrder.class));
    }

    /** 首次尝试（历史缺失）按第 1 次处理，不因空历史报错。 */
    @Test
    void shouldTreatMissingHistoryAsFirstAttempt() {
        when(settlementMapper.selectById(50L))
                .thenReturn(settlement(SettlementStatus.SETTLEMENT_PENDING_RETRY));
        when(payoutOrderMapper.selectOne(any())).thenReturn(null);
        when(runtimeConfig.maxAttempts()).thenReturn(3);
        when(payoutOrderMapper.insert(any(PayoutOrder.class))).thenReturn(1);
        when(settlementMapper.update(any(), any())).thenReturn(1);

        service.startNextAttempt(50L);

        ArgumentCaptor<PayoutOrder> captor = ArgumentCaptor.forClass(PayoutOrder.class);
        verify(payoutOrderMapper).insert(captor.capture());
        assertThat(captor.getValue().getAttemptSeq()).isEqualTo(1);
    }
}
