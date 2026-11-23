package com.escrow.platform.ai.infrastructure.llm;

import com.escrow.platform.ai.domain.AiPhase;
import com.escrow.platform.ai.infrastructure.cost.CostDailyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 落库版成本账本（V10-c1 修复）：累加、回填、降级三条行为。
 *
 * <p>三个用例对应三个曾经真实存在的缺口：重启清零（回填）、写库拖垮检索（降级）、
 * 规则阶段被写进 CHECK 不允许的枚举值（跳过）。</p>
 *
 * @since V10
 */
@ExtendWith(MockitoExtension.class)
class PersistentAiCostLedgerTest {

    private static final int BUDGET = 1000;

    @Mock
    private CostDailyStore store;

    private InMemoryAiCostLedger local;
    private PersistentAiCostLedger ledger;

    @BeforeEach
    void setUp() {
        // 默认"库里今天还没记账"；个别用例会把它改成有基线或抛错，故用 lenient
        org.mockito.Mockito.lenient().when(store.usageOn(any(LocalDate.class)))
                .thenReturn(CostDailyStore.DailyUsage.empty());
        local = new InMemoryAiCostLedger(BUDGET);
        ledger = new PersistentAiCostLedger(local, store);
    }

    /** 记账必须原子落到当天该阶段，进程内计数同时生效。 */
    @Test
    void v10c1RecordAccumulatesToDailyStore() {
        ledger.record(AiPhase.RERANK, 120, 360L);

        verify(store).accumulate(eq(LocalDate.now()), eq(AiPhase.RERANK), eq(120), eq(360L));
        assertThat(ledger.usedTokens(AiPhase.RERANK)).isEqualTo(120);
        assertThat(ledger.costMicrosToday()).isEqualTo(360L);
    }

    /** 重启后预算基线从库里恢复：库里已有 900，预算 1000，再要 200 必须拒。 */
    @Test
    void v10c1SeedsBudgetBaselineFromStore() {
        when(store.usageOn(any(LocalDate.class))).thenReturn(
                new CostDailyStore.DailyUsage(Map.of(AiPhase.UNDERSTAND, 900), 2700L));

        assertThat(ledger.allow(AiPhase.UNDERSTAND, 200)).isFalse();
        assertThat(ledger.usedTokens(AiPhase.UNDERSTAND)).isEqualTo(900);
        assertThat(ledger.costMicrosToday()).isEqualTo(2700L);
    }

    /** 写库失败不阻断业务；连续失败达到阈值后关掉落库，不再白试。 */
    @Test
    void v10c1PersistFailureDegradesWithoutBreakingBusiness() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(store).accumulate(any(LocalDate.class), any(AiPhase.class), anyInt(), anyLong());

        ledger.record(AiPhase.GENERATE, 10, 30L);
        assertThat(ledger.usedTokens(AiPhase.GENERATE)).as("记账照旧").isEqualTo(10);
        assertThat(ledger.persistEnabled()).isTrue();

        ledger.record(AiPhase.GENERATE, 10, 30L);
        ledger.record(AiPhase.GENERATE, 10, 30L);
        assertThat(ledger.persistEnabled()).as("连续 3 次失败后熔断").isFalse();

        ledger.record(AiPhase.GENERATE, 10, 30L);
        verify(store, times(PersistentAiCostLedger.MAX_FAILURES))
                .accumulate(any(LocalDate.class), any(AiPhase.class), anyInt(), anyLong());
        assertThat(ledger.usedTokens(AiPhase.GENERATE)).as("熔断后仍记账").isEqualTo(40);
    }

    /** 规则阶段（FILTER / RECALL）不产生费用，也不写 ai_cost_daily。 */
    @Test
    void v10c1RulePhasesAreNotPersisted() {
        ledger.record(AiPhase.FILTER, 50, 0L);
        ledger.record(AiPhase.RECALL, 50, 0L);

        verify(store, times(0)).accumulate(any(LocalDate.class), any(AiPhase.class), anyInt(), anyLong());
    }

    /** 读库失败按 0 起算，不能因为回填失败让第一次检索起不来。 */
    @Test
    void v10c1SeedFailureFallsBackToZero() {
        when(store.usageOn(any(LocalDate.class))).thenThrow(new IllegalStateException("db down"));

        assertThat(ledger.allow(AiPhase.GENERATE, 100)).isTrue();
        assertThat(ledger.usedTokens(AiPhase.GENERATE)).isZero();
    }
}
