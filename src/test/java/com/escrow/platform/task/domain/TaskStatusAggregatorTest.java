package com.escrow.platform.task.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务展示状态聚合器单测（v2-c2 卡片 §8 第 1 行，20 个用例）。
 *
 * <p>覆盖：R0~R12 每条至少 1 例；R1 压过 R2/R3 与 R7~R10；R4 先于 R5/R6；
 * {@code total()==0} 不得落进 R4/R5/R6（走 R12 兜底）；R11 非终态；
 * 两个异常标记方法；{@code TaskDisplayStatus} 全值 ≤28 字符。</p>
 *
 * @since V2
 */
class TaskStatusAggregatorTest {

    /** 非草稿、未撤单、未收口、全部计数为 0 的基线。 */
    private static TaskStatusFacts facts() {
        return new TaskStatusFacts(false, false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** R0 草稿：即使名额与争议计数非零也必须最先命中（草稿没有名额）。 */
    @Test
    void r0DraftShouldBeatEverything() {
        TaskStatusFacts f = new TaskStatusFacts(
                true, false, false, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        assertThat(TaskStatusAggregator.aggregate(f)).isEqualTo(TaskDisplayStatus.TASK_DRAFT);
    }

    /** R1 招募中：存在待占用且未收口。 */
    @Test
    void r1RecruitingWhenAvailableAndOpen() {
        assertThat(TaskStatusAggregator.aggregate(withAvailable(1)))
                .isEqualTo(TaskDisplayStatus.TASK_RECRUITING);
    }

    /** R1 压过 R2/R3（SRS v1.2 P1-01）：还剩待占用就不许显示争议中 / 结算异常。 */
    @Test
    void r1ShouldBeatDisputeAndSettlementAbnormal() {
        assertThat(TaskStatusAggregator.aggregate(withAvailableAndArbitration(1, 1, 1)))
                .isEqualTo(TaskDisplayStatus.TASK_RECRUITING);
        assertThat(TaskStatusAggregator.aggregate(withAvailableAndArbitration(1, 1, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_RECRUITING);
    }

    /** R1 压过 R7~R10（SRS v1.1 P1-01）：第一个名额进入后续阶段不许压住招募中。 */
    @Test
    void r1ShouldBeatSettlingPendingAcceptanceInProgressAndTaken() {
        assertThat(TaskStatusAggregator.aggregate(withAvailablePlus(1, 0, 0, 0, 1, 0, 0, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_RECRUITING);
        assertThat(TaskStatusAggregator.aggregate(withAvailablePlus(1, 0, 0, 1, 0, 0, 0, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_RECRUITING);
        assertThat(TaskStatusAggregator.aggregate(withAvailablePlus(1, 0, 1, 0, 0, 0, 0, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_RECRUITING);
        assertThat(TaskStatusAggregator.aggregate(withAvailablePlus(1, 1, 0, 0, 0, 0, 0, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_RECRUITING);
    }

    /** R2 争议中：存在待裁决名额且有未裁决争议单。 */
    @Test
    void r2InDisputeWhenArbitrationWithOpenDispute() {
        assertThat(TaskStatusAggregator.aggregate(
                new TaskStatusFacts(false, false, false, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1)))
                .isEqualTo(TaskDisplayStatus.TASK_IN_DISPUTE);
    }

    /** R3 结算异常：存在待裁决名额但无未裁决争议单（打款人工失败产生）。 */
    @Test
    void r3SettlementAbnormalWhenArbitrationWithoutDispute() {
        assertThat(TaskStatusAggregator.aggregate(
                new TaskStatusFacts(false, false, false, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_SETTLEMENT_ABNORMAL);
    }

    /** R4 已取消：全部名额终态且撤单标志为真。 */
    @Test
    void r4CancelledWhenAllTerminalAndCancelled() {
        assertThat(TaskStatusAggregator.aggregate(
                new TaskStatusFacts(false, true, true, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_CANCELLED);
    }

    /** R4 先于 R5/R6：全部终态时先看撤单标志，不许落到已完成 / 已关闭。 */
    @Test
    void r4ShouldBeatCompletedAndClosed() {
        assertThat(TaskStatusAggregator.aggregate(
                new TaskStatusFacts(false, true, true, 0, 0, 0, 0, 0, 0, 2, 1, 0, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_CANCELLED);
        assertThat(TaskStatusAggregator.aggregate(
                new TaskStatusFacts(false, true, true, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_CANCELLED);
    }

    /** R5 已完成：全部终态且至少一个已完结，无撤单标志。 */
    @Test
    void r5CompletedWhenAllTerminalWithCompleted() {
        assertThat(TaskStatusAggregator.aggregate(
                new TaskStatusFacts(false, false, false, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_COMPLETED);
    }

    /** R6 已关闭：全部终态且无已完结（如全部已退款 / 已释放）。 */
    @Test
    void r6ClosedWhenAllTerminalWithoutCompleted() {
        assertThat(TaskStatusAggregator.aggregate(
                new TaskStatusFacts(false, false, false, 0, 0, 0, 0, 0, 0, 0, 2, 1, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_CLOSED);
    }

    /** R7 结算中：存在待结算名额（名额已验收、只等打款，不能显示待验收）。 */
    @Test
    void r7SettlingWhenPendingSettlementExists() {
        assertThat(TaskStatusAggregator.aggregate(
                new TaskStatusFacts(false, false, false, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_SETTLING);
    }

    /** R8 待验收：存在已交付名额。 */
    @Test
    void r8PendingAcceptanceWhenDeliveredExists() {
        assertThat(TaskStatusAggregator.aggregate(
                new TaskStatusFacts(false, false, false, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_PENDING_ACCEPTANCE);
    }

    /** R9 履约中：存在履约中名额。 */
    @Test
    void r9InProgressWhenInProgressExists() {
        assertThat(TaskStatusAggregator.aggregate(
                new TaskStatusFacts(false, false, false, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_IN_PROGRESS);
    }

    /** R10 已接单：存在已占用、无待占用且招募未收口。 */
    @Test
    void r10TakenWhenOnlyOccupiedAndOpen() {
        assertThat(TaskStatusAggregator.aggregate(
                new TaskStatusFacts(false, false, false, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_TAKEN);
    }

    /** R11 已关闭招募：收口但仍非终态名额在途，进度仍要能看见。 */
    @Test
    void r11RecruitingClosedWhenClosedWithNonTerminalSlots() {
        assertThat(TaskStatusAggregator.aggregate(
                new TaskStatusFacts(false, false, true, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_RECRUITING_CLOSED);
    }

    /** R11 非终态：同一任务在名额全部终态后必须落到 R5/R6，不许困在已关闭招募。 */
    @Test
    void r11ShouldNotTrapTerminalSlots() {
        assertThat(TaskStatusAggregator.aggregate(
                new TaskStatusFacts(false, false, true, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0)))
                .isEqualTo(TaskDisplayStatus.TASK_COMPLETED);
    }

    /**
     * total()==0 不得落进 R4/R5/R6（走 R12）：已发布但零名额的任务既不是「已完成/已取消」
     * 也不是「全部终态」——allTerminal 的 {@code total() > 0} 守卫是本卡最易错处。
     */
    @Test
    void zeroSlotsShouldFallThroughToR12Closed() {
        TaskStatusFacts f = facts();
        assertThat(f.allTerminal()).as("零名额不算全部终态").isFalse();
        assertThat(TaskStatusAggregator.aggregate(f)).isEqualTo(TaskDisplayStatus.TASK_CLOSED);
    }

    /** 标记方法 {@code hasOpenDispute}：未裁决争议单数 &gt; 0。 */
    @Test
    void hasOpenDisputeShouldFollowOpenDisputeCount() {
        assertThat(TaskStatusAggregator.hasOpenDispute(facts())).isFalse();
        assertThat(TaskStatusAggregator.hasOpenDispute(
                new TaskStatusFacts(false, false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2))).isTrue();
    }

    /** 标记方法 {@code hasSettlementAbnormal}：待裁决名额存在但无未裁决争议单。 */
    @Test
    void hasSettlementAbnormalShouldRequireArbitrationWithoutDispute() {
        assertThat(TaskStatusAggregator.hasSettlementAbnormal(facts())).isFalse();
        assertThat(TaskStatusAggregator.hasSettlementAbnormal(
                new TaskStatusFacts(false, false, false, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1))).isFalse();
        assertThat(TaskStatusAggregator.hasSettlementAbnormal(
                new TaskStatusFacts(false, false, false, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0))).isTrue();
    }

    /** 展示状态常量名 ≤28 字符（docs/decisions.md #20【V2 修订】；最长 TASK_SETTLEMENT_ABNORMAL = 24）。 */
    @Test
    void displayStatusNamesShouldNotExceed28Chars() {
        for (TaskDisplayStatus status : TaskDisplayStatus.values()) {
            assertThat(status.name().length())
                    .as("%s 常量名必须 ≤28 字符", status.name())
                    .isLessThanOrEqualTo(28);
        }
        assertThat(TaskDisplayStatus.values()).hasSize(12);
    }

    // ==================== 构造辅助 ====================

    private static TaskStatusFacts withAvailable(int available) {
        return new TaskStatusFacts(false, false, false, available, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static TaskStatusFacts withAvailableAndArbitration(int available, int pendingArbitration, int openDisputes) {
        return new TaskStatusFacts(false, false, false,
                available, 0, 0, 0, pendingArbitration, 0, 0, 0, 0, openDisputes);
    }

    private static TaskStatusFacts withAvailablePlus(int available, int occupied, int inProgress,
                                                     int delivered, int pendingSettlement,
                                                     int completed, int refunded, int released) {
        return new TaskStatusFacts(false, false, false,
                available, occupied, inProgress, delivered, 0, pendingSettlement,
                completed, refunded, released, 0);
    }
}
