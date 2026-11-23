package com.escrow.platform.task.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 撤单资格分类器单测（v2-c2 卡片 §8 第 2 行，8 个用例）。
 *
 * <p>覆盖：草稿、零名额、全待占用、存在已占用、存在履约中、全终态、
 * 待占用 + 终态混合、仅已释放。</p>
 *
 * @since V2
 */
class TaskCancelEligibilityClassifierTest {

    /** 非草稿、未撤单、未收口、全部计数为 0 的基线。 */
    private static TaskStatusFacts facts() {
        return new TaskStatusFacts(false, false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** 草稿：撤单只对已发布任务有意义。 */
    @Test
    void draftShouldNotBeCancelable() {
        TaskStatusFacts f = new TaskStatusFacts(
                true, false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(TaskCancelEligibilityClassifier.classify(f))
                .isEqualTo(TaskCancelEligibility.NOT_CANCELABLE);
    }

    /** 零名额（已发布但无名额行）：不可撤。 */
    @Test
    void zeroSlotsShouldNotBeCancelable() {
        assertThat(TaskCancelEligibilityClassifier.classify(facts()))
                .isEqualTo(TaskCancelEligibility.NOT_CANCELABLE);
    }

    /** 全部名额待占用：FR-TASK.CANCEL.01 的唯一可撤前提。 */
    @Test
    void allAvailableShouldBeCancelable() {
        assertThat(TaskCancelEligibilityClassifier.classify(
                new TaskStatusFacts(false, false, false, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
                .isEqualTo(TaskCancelEligibility.CANCELABLE);
    }

    /** 存在已占用名额：HAS_ACTIVE_SLOT（FR-TASK.CANCEL.02 的 TASK_HAS_ACTIVE_SLOT）。 */
    @Test
    void occupiedSlotShouldBlockCancellation() {
        assertThat(TaskCancelEligibilityClassifier.classify(
                new TaskStatusFacts(false, false, false, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0)))
                .isEqualTo(TaskCancelEligibility.HAS_ACTIVE_SLOT);
    }

    /** 存在履约中名额：HAS_ACTIVE_SLOT。 */
    @Test
    void inProgressSlotShouldBlockCancellation() {
        assertThat(TaskCancelEligibilityClassifier.classify(
                new TaskStatusFacts(false, false, false, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0)))
                .isEqualTo(TaskCancelEligibility.HAS_ACTIVE_SLOT);
    }

    /** 全部名额终态（已完结）：不可撤，FR-TASK.CANCEL.02 的 TASK_NOT_CANCELABLE。 */
    @Test
    void allTerminalShouldNotBeCancelable() {
        assertThat(TaskCancelEligibilityClassifier.classify(
                new TaskStatusFacts(false, false, false, 0, 0, 0, 0, 0, 0, 2, 1, 0, 0)))
                .isEqualTo(TaskCancelEligibility.NOT_CANCELABLE);
    }

    /** 待占用 + 终态混合：FR-TASK.CANCEL.01 要求全部待占用，混合归 TASK_NOT_CANCELABLE，不让已完结名额被误退款。 */
    @Test
    void mixedAvailableAndTerminalShouldNotBeCancelable() {
        assertThat(TaskCancelEligibilityClassifier.classify(
                new TaskStatusFacts(false, false, false, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0)))
                .isEqualTo(TaskCancelEligibility.NOT_CANCELABLE);
    }

    /** 仅已释放：全部终态且无已完结，归 NOT_CANCELABLE。 */
    @Test
    void releasedOnlyShouldNotBeCancelable() {
        assertThat(TaskCancelEligibilityClassifier.classify(
                new TaskStatusFacts(false, false, false, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0)))
                .isEqualTo(TaskCancelEligibility.NOT_CANCELABLE);
    }
}
