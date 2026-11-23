package com.escrow.platform.task.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TaskDetailVisibility} 纯函数（v2-c3 卡片 §6.2 / §8 第 2 行）。
 *
 * <p>不取 {@code now}，全部传值断言；覆盖「雇主本人 / 非本人 / 未登录 / 持有名额」与
 * 「已发布 / 草稿 / 已撤单」四种可见性组合。</p>
 *
 * @since V2
 */
class TaskDetailVisibilityTest {

    private static final Long EMPLOYER_ID = 1001L;
    private static final Long OTHER_ID = 2002L;
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 11, 20, 10, 0);

    /** 雇主本人是当事人。 */
    @Test
    void employerShouldBeParty() {
        assertThat(TaskDetailVisibility.isParty(EMPLOYER_ID, EMPLOYER_ID, false)).isTrue();
    }

    /** 非雇主且不持有名额不是当事人。 */
    @Test
    void otherUserWithoutSlotShouldNotBeParty() {
        assertThat(TaskDetailVisibility.isParty(EMPLOYER_ID, OTHER_ID, false)).isFalse();
    }

    /** 未登录（viewer=null）不是当事人。 */
    @Test
    void anonymousShouldNotBeParty() {
        assertThat(TaskDetailVisibility.isParty(EMPLOYER_ID, null, false)).isFalse();
    }

    /** 持有名额（c4 接入后）即使不是雇主也是当事人。 */
    @Test
    void viewerWithSlotShouldBeParty() {
        assertThat(TaskDetailVisibility.isParty(EMPLOYER_ID, OTHER_ID, true)).isTrue();
    }

    /** 已发布任务对所有人可见。 */
    @Test
    void publishedTaskShouldBeVisibleToAnyone() {
        assertThat(TaskDetailVisibility.isVisibleTo(PUBLISHED_AT, false)).isTrue();
    }

    /** 草稿对当事人可见。 */
    @Test
    void draftShouldBeVisibleToParty() {
        assertThat(TaskDetailVisibility.isVisibleTo(null, true)).isTrue();
    }

    /** 草稿对非当事人不可见（404，不暴露存在性）。 */
    @Test
    void draftShouldBeInvisibleToNonParty() {
        assertThat(TaskDetailVisibility.isVisibleTo(null, false)).isFalse();
    }

    /** 已撤单但已发布（publishedAt 非空）仍可读，历史不可抹掉。 */
    @Test
    void cancelledButPublishedShouldStillBeVisible() {
        assertThat(TaskDetailVisibility.isVisibleTo(PUBLISHED_AT, false)).isTrue();
    }
}
