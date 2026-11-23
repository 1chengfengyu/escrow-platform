package com.escrow.platform.task.application;

import com.escrow.platform.task.api.TaskDraftRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务草稿字段校验单测（v2-c2 卡片 §8 第 4 行，16 个用例）。
 *
 * <p>覆盖 FR-TASK.CREATE.02 全部字段的正反例；特别卡死三处边界：
 * {@code startTime == now} 允许（{@code isBefore} 判定）、
 * {@code endTime == startTime} 拒绝（「晚于」是严格不等）、
 * 已发布任务（{@code requireFutureStart=false}）允许过去时间。</p>
 *
 * @since V2
 */
class TaskValidatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 10, 9, 0, 0);

    private final TaskValidator validator = new TaskValidator();

    /** 全部合法的基线请求。 */
    private static TaskDraftRequest valid() {
        return new TaskDraftRequest(
                "周末小区派单", "帮忙派发传单", "地推", 5000L, 3,
                NOW.plusDays(1), NOW.plusDays(2), "北京市朝阳区", "验收后结算");
    }

    private static TaskDraftRequest withTitle(String title) {
        return new TaskDraftRequest(title, valid().description(), valid().category(),
                5000L, 3, NOW.plusDays(1), NOW.plusDays(2), "北京市朝阳区", null);
    }

    /** 正例：全部字段合法，无错误。 */
    @Test
    void validRequestShouldPass() {
        assertThat(validator.validate(valid(), NOW, true)).isEmpty();
    }

    /** 标题必填：null 与空白串都算错。 */
    @Test
    void titleShouldBeRequired() {
        assertThat(validator.validate(withTitle(null), NOW, true)).containsOnlyKeys("title");
        assertThat(validator.validate(withTitle("   "), NOW, true)).containsOnlyKeys("title");
    }

    /** 标题 ≤60 字（按字符数）。 */
    @Test
    void titleShouldNotExceed60Chars() {
        assertThat(validator.validate(withTitle("好".repeat(61)), NOW, true)).containsOnlyKeys("title");
        assertThat(validator.validate(withTitle("好".repeat(60)), NOW, true)).isEmpty();
    }

    /** 描述必填。 */
    @Test
    void descriptionShouldBeRequired() {
        TaskDraftRequest req = new TaskDraftRequest("标题", null, "地推",
                5000L, 3, NOW.plusDays(1), NOW.plusDays(2), "北京市朝阳区", null);
        assertThat(validator.validate(req, NOW, true)).containsOnlyKeys("description");
    }

    /** 描述 ≤2000 字。 */
    @Test
    void descriptionShouldNotExceed2000Chars() {
        TaskDraftRequest req = new TaskDraftRequest("标题", "好".repeat(2001), "地推",
                5000L, 3, NOW.plusDays(1), NOW.plusDays(2), "北京市朝阳区", null);
        assertThat(validator.validate(req, NOW, true)).containsOnlyKeys("description");
    }

    /** 类别必填。 */
    @Test
    void categoryShouldBeRequired() {
        TaskDraftRequest req = new TaskDraftRequest("标题", "描述", "  ",
                5000L, 3, NOW.plusDays(1), NOW.plusDays(2), "北京市朝阳区", null);
        assertThat(validator.validate(req, NOW, true)).containsOnlyKeys("category");
    }

    /** 类别 ≤32 字。 */
    @Test
    void categoryShouldNotExceed32Chars() {
        TaskDraftRequest req = new TaskDraftRequest("标题", "描述", "好".repeat(33),
                5000L, 3, NOW.plusDays(1), NOW.plusDays(2), "北京市朝阳区", null);
        assertThat(validator.validate(req, NOW, true)).containsOnlyKeys("category");
    }

    /** 单价必填且 &gt; 0（单位分）。 */
    @Test
    void unitPriceShouldBeRequiredAndPositive() {
        TaskDraftRequest missing = new TaskDraftRequest("标题", "描述", "地推",
                null, 3, NOW.plusDays(1), NOW.plusDays(2), "北京市朝阳区", null);
        assertThat(validator.validate(missing, NOW, true)).containsOnlyKeys("unitPrice");
        TaskDraftRequest zero = new TaskDraftRequest("标题", "描述", "地推",
                0L, 3, NOW.plusDays(1), NOW.plusDays(2), "北京市朝阳区", null);
        assertThat(validator.validate(zero, NOW, true)).containsOnlyKeys("unitPrice");
        TaskDraftRequest negative = new TaskDraftRequest("标题", "描述", "地推",
                -1L, 3, NOW.plusDays(1), NOW.plusDays(2), "北京市朝阳区", null);
        assertThat(validator.validate(negative, NOW, true)).containsOnlyKeys("unitPrice");
    }

    /** 名额数必填且在 1~20（FR-TASK.CREATE.02）。 */
    @Test
    void slotCountShouldBeRequiredAndWithinRange() {
        for (Integer count : new Integer[]{null, 0, 21}) {
            TaskDraftRequest req = new TaskDraftRequest("标题", "描述", "地推",
                    5000L, count, NOW.plusDays(1), NOW.plusDays(2), "北京市朝阳区", null);
            assertThat(validator.validate(req, NOW, true)).containsOnlyKeys("slotCount");
        }
    }

    /** 时间必填：开始 / 结束缺一不可。 */
    @Test
    void timeFieldsShouldBeRequired() {
        TaskDraftRequest noStart = new TaskDraftRequest("标题", "描述", "地推",
                5000L, 3, null, NOW.plusDays(2), "北京市朝阳区", null);
        assertThat(validator.validate(noStart, NOW, true)).containsOnlyKeys("startTime");
        TaskDraftRequest noEnd = new TaskDraftRequest("标题", "描述", "地推",
                5000L, 3, NOW.plusDays(1), null, "北京市朝阳区", null);
        assertThat(validator.validate(noEnd, NOW, true)).containsOnlyKeys("endTime");
    }

    /** 结束时间必须晚于开始时间：相等与早于都拒绝。 */
    @Test
    void endTimeMustBeStrictlyAfterStartTime() {
        TaskDraftRequest equal = new TaskDraftRequest("标题", "描述", "地推",
                5000L, 3, NOW.plusDays(1), NOW.plusDays(1), "北京市朝阳区", null);
        assertThat(validator.validate(equal, NOW, true)).containsOnlyKeys("endTime");
        TaskDraftRequest before = new TaskDraftRequest("标题", "描述", "地推",
                5000L, 3, NOW.plusDays(2), NOW.plusDays(1), "北京市朝阳区", null);
        assertThat(validator.validate(before, NOW, true)).containsOnlyKeys("endTime");
    }

    /** startTime == now 允许：用 isBefore 判定，等于不算早于。 */
    @Test
    void startTimeEqualToNowShouldBeAllowed() {
        TaskDraftRequest req = new TaskDraftRequest("标题", "描述", "地推",
                5000L, 3, NOW, NOW.plusDays(1), "北京市朝阳区", null);
        assertThat(validator.validate(req, NOW, true)).isEmpty();
    }

    /** 草稿要求开始时间不得早于当前时间（FR-TASK.CREATE.02）。 */
    @Test
    void pastStartTimeShouldBeRejectedForDraft() {
        TaskDraftRequest req = new TaskDraftRequest("标题", "描述", "地推",
                5000L, 3, NOW.minusMinutes(1), NOW.plusDays(1), "北京市朝阳区", null);
        assertThat(validator.validate(req, NOW, true)).containsOnlyKeys("startTime");
    }

    /** 已发布任务编辑允许过去时间（FR-TASK.CREATE.05，卡片 §1.3 第 9 条）。 */
    @Test
    void publishedTaskShouldAllowPastStartTime() {
        TaskDraftRequest req = new TaskDraftRequest("标题", "描述", "地推",
                5000L, 3, NOW.minusDays(1), NOW.plusDays(1), "北京市朝阳区", null);
        assertThat(validator.validate(req, NOW, false)).isEmpty();
    }

    /** 工作地点必填且 ≤128 字。 */
    @Test
    void workLocationShouldBeRequiredWithin128Chars() {
        TaskDraftRequest missing = new TaskDraftRequest("标题", "描述", "地推",
                5000L, 3, NOW.plusDays(1), NOW.plusDays(2), " ", null);
        assertThat(validator.validate(missing, NOW, true)).containsOnlyKeys("workLocation");
        TaskDraftRequest tooLong = new TaskDraftRequest("标题", "描述", "地推",
                5000L, 3, NOW.plusDays(1), NOW.plusDays(2), "好".repeat(129), null);
        assertThat(validator.validate(tooLong, NOW, true)).containsOnlyKeys("workLocation");
    }

    /** 结算说明可空，非空时 ≤500 字；恰好 500 通过。 */
    @Test
    void settlementNoteShouldBeOptionalWithin500Chars() {
        TaskDraftRequest ok = valid();
        assertThat(validator.validate(ok, NOW, true)).isEmpty();
        TaskDraftRequest boundary = new TaskDraftRequest("标题", "描述", "地推",
                5000L, 3, NOW.plusDays(1), NOW.plusDays(2), "北京市朝阳区", "好".repeat(500));
        assertThat(validator.validate(boundary, NOW, true)).isEmpty();
        TaskDraftRequest tooLong = new TaskDraftRequest("标题", "描述", "地推",
                5000L, 3, NOW.plusDays(1), NOW.plusDays(2), "北京市朝阳区", "好".repeat(501));
        assertThat(validator.validate(tooLong, NOW, true)).containsOnlyKeys("settlementNote");
    }
}
