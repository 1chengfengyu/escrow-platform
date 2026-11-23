package com.escrow.platform.task.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 草稿编辑策略单测（v2-c2 卡片 §8 第 3 行，4 个用例）。
 *
 * @since V2
 */
class TaskEditPolicyTest {

    private static final Long OLD_PRICE = 10000L;
    private static final Integer OLD_COUNT = 3;

    /** 已发布改单价：价格锁命中（FR-TASK.CREATE.05）。 */
    @Test
    void publishedPriceChangeShouldBeLocked() {
        assertThat(TaskEditPolicy.priceOrSlotChanged(true, OLD_PRICE, OLD_COUNT, 20000L, OLD_COUNT))
                .isTrue();
    }

    /** 已发布改名额数：价格锁命中。 */
    @Test
    void publishedSlotCountChangeShouldBeLocked() {
        assertThat(TaskEditPolicy.priceOrSlotChanged(true, OLD_PRICE, OLD_COUNT, OLD_PRICE, 4))
                .isTrue();
    }

    /** 已发布只改其他字段（单价与名额数原样）：不触发价格锁。 */
    @Test
    void publishedOtherFieldsChangeShouldNotBeLocked() {
        assertThat(TaskEditPolicy.priceOrSlotChanged(true, OLD_PRICE, OLD_COUNT, OLD_PRICE, OLD_COUNT))
                .isFalse();
    }

    /** 草稿改单价：不触发价格锁（价格锁只约束「招募中」之后）。 */
    @Test
    void draftPriceChangeShouldNotBeLocked() {
        assertThat(TaskEditPolicy.priceOrSlotChanged(false, OLD_PRICE, OLD_COUNT, 20000L, 5))
                .isFalse();
    }
}
