package com.escrow.platform.task.domain;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link TaskSearchCriteria} 解析与校验（v2-c3 卡片 §4.2 / §8 第 1 行）。
 *
 * <p>纯单测：不连 Spring、不连 MySQL。逐条覆盖「默认值 / trim / 长度 / 价格 / 时间 / 分页 /
 * 排序白名单」，每个失败分支都要求 {@code INVALID_PARAMETER} 且 {@code data.fields} 指明字段。</p>
 *
 * @since V2
 */
class TaskSearchCriteriaTest {

    private static final int KEYWORD = 0;
    private static final int CATEGORY = 1;
    private static final int MIN_PRICE = 2;
    private static final int MAX_PRICE = 3;
    private static final int FROM = 4;
    private static final int TO = 5;
    private static final int LOCATION = 6;
    private static final int PAGE = 7;
    private static final int SIZE = 8;
    private static final int SORT = 9;
    private static final int ORDER = 10;

    /** 全空入参：默认 page=1 / size=20 / sort=PUBLISHED_AT / order=DESC、不筛任何条件。 */
    @Test
    void shouldApplyDefaultsWhenAllParamsAbsent() {
        TaskSearchCriteria criteria = parse(new String[11]);

        assertThat(criteria.keyword()).isNull();
        assertThat(criteria.category()).isNull();
        assertThat(criteria.minPrice()).isNull();
        assertThat(criteria.maxPrice()).isNull();
        assertThat(criteria.from()).isNull();
        assertThat(criteria.to()).isNull();
        assertThat(criteria.location()).isNull();
        assertThat(criteria.page()).isEqualTo(1);
        assertThat(criteria.size()).isEqualTo(20);
        assertThat(criteria.sort()).isEqualTo(TaskSortField.PUBLISHED_AT);
        assertThat(criteria.order()).isEqualTo(SortDirection.DESC);
    }

    /** 关键词 trim 后保留。 */
    @Test
    void keywordShouldBeTrimmed() {
        assertThat(only(KEYWORD, "  周末派单  ").keyword()).isEqualTo("周末派单");
    }

    /** 关键词纯空白视为不筛。 */
    @Test
    void blankKeywordShouldBecomeNull() {
        assertThat(only(KEYWORD, "   ").keyword()).isNull();
    }

    /** 关键词 51 字超长：400 且指明 keyword。 */
    @Test
    void keywordOver50ShouldBeRejected() {
        expectInvalid(() -> only(KEYWORD, "a".repeat(51)), "keyword");
    }

    /** 类别 trim 后保留。 */
    @Test
    void categoryShouldBeTrimmed() {
        assertThat(only(CATEGORY, "  地推 ").category()).isEqualTo("地推");
    }

    /** 类别纯空白视为不筛。 */
    @Test
    void blankCategoryShouldBecomeNull() {
        assertThat(only(CATEGORY, " ").category()).isNull();
    }

    /** 类别 33 字超长：400 且指明 category。 */
    @Test
    void categoryOver32ShouldBeRejected() {
        expectInvalid(() -> only(CATEGORY, "a".repeat(33)), "category");
    }

    /** 地点 trim 后保留。 */
    @Test
    void locationShouldBeTrimmed() {
        assertThat(only(LOCATION, " 北京市朝阳区 ").location()).isEqualTo("北京市朝阳区");
    }

    /** 地点纯空白视为不筛。 */
    @Test
    void blankLocationShouldBecomeNull() {
        assertThat(only(LOCATION, "").location()).isNull();
    }

    /** 地点 129 字超长：400 且指明 location。 */
    @Test
    void locationOver128ShouldBeRejected() {
        expectInvalid(() -> only(LOCATION, "a".repeat(129)), "location");
    }

    /** 单边下限正常解析（单位分）。 */
    @Test
    void minPriceShouldBeParsed() {
        TaskSearchCriteria criteria = only(MIN_PRICE, "5000");
        assertThat(criteria.minPrice()).isEqualTo(5000L);
        assertThat(criteria.maxPrice()).isNull();
    }

    /** 单边上限正常解析。 */
    @Test
    void maxPriceShouldBeParsed() {
        TaskSearchCriteria criteria = only(MAX_PRICE, "9000");
        assertThat(criteria.maxPrice()).isEqualTo(9000L);
        assertThat(criteria.minPrice()).isNull();
    }

    /** 价格空串视为不筛。 */
    @Test
    void blankPriceShouldBecomeNull() {
        assertThat(only(MIN_PRICE, "  ").minPrice()).isNull();
    }

    /** 价格 0 非法。 */
    @Test
    void zeroPriceShouldBeRejected() {
        expectInvalid(() -> only(MIN_PRICE, "0"), "minPrice");
    }

    /** 价格负数非法。 */
    @Test
    void negativePriceShouldBeRejected() {
        expectInvalid(() -> only(MAX_PRICE, "-1"), "maxPrice");
    }

    /** 价格非数字非法。 */
    @Test
    void nonNumericPriceShouldBeRejected() {
        expectInvalid(() -> only(MIN_PRICE, "abc"), "minPrice");
    }

    /** 区间倒置（min > max）非法，字段指向 minPrice。 */
    @Test
    void minGreaterThanMaxShouldBeRejected() {
        expectInvalid(() -> pair(MIN_PRICE, "100", MAX_PRICE, "99"), "minPrice");
    }

    /** from / to 正常解析为 LocalDateTime。 */
    @Test
    void fromAndToShouldBeParsed() {
        TaskSearchCriteria criteria = pair(FROM, "2026-12-01T09:00:00", TO, "2026-12-02T18:00:00");
        assertThat(criteria.from()).isEqualTo(LocalDateTime.of(2026, 12, 1, 9, 0));
        assertThat(criteria.to()).isEqualTo(LocalDateTime.of(2026, 12, 2, 18, 0));
    }

    /** 时间空串视为不筛。 */
    @Test
    void blankTimeShouldBecomeNull() {
        assertThat(only(FROM, " ").from()).isNull();
    }

    /** 时间格式不是 ISO 非法。 */
    @Test
    void slashDateShouldBeRejected() {
        expectInvalid(() -> only(FROM, "2026/12/01"), "from");
    }

    /** 月份 13 非法。 */
    @Test
    void invalidMonthShouldBeRejected() {
        expectInvalid(() -> only(TO, "2026-13-01T00:00:00"), "to");
    }

    /** from 晚于 to 非法。 */
    @Test
    void fromAfterToShouldBeRejected() {
        expectInvalid(() -> pair(FROM, "2026-12-02T00:00:00", TO, "2026-12-01T00:00:00"), "from");
    }

    /** 只给一端（from）也合法。 */
    @Test
    void singleSidedFromShouldBeAccepted() {
        TaskSearchCriteria criteria = only(FROM, "2026-12-01T00:00:00");
        assertThat(criteria.from()).isNotNull();
        assertThat(criteria.to()).isNull();
    }

    /** page=0 非法。 */
    @Test
    void zeroPageShouldBeRejected() {
        expectInvalid(() -> only(PAGE, "0"), "page");
    }

    /** page=-1 非法。 */
    @Test
    void negativePageShouldBeRejected() {
        expectInvalid(() -> only(PAGE, "-1"), "page");
    }

    /** page 非数字非法。 */
    @Test
    void nonNumericPageShouldBeRejected() {
        expectInvalid(() -> only(PAGE, "abc"), "page");
    }

    /** page 正常解析。 */
    @Test
    void pageShouldBeParsed() {
        assertThat(only(PAGE, "3").page()).isEqualTo(3);
    }

    /** size=0 非法。 */
    @Test
    void zeroSizeShouldBeRejected() {
        expectInvalid(() -> only(SIZE, "0"), "size");
    }

    /** size=101 非法。 */
    @Test
    void sizeOver100ShouldBeRejected() {
        expectInvalid(() -> only(SIZE, "101"), "size");
    }

    /** size 非数字非法。 */
    @Test
    void nonNumericSizeShouldBeRejected() {
        expectInvalid(() -> only(SIZE, "abc"), "size");
    }

    /** size=100 是合法上界。 */
    @Test
    void size100ShouldBeAccepted() {
        assertThat(only(SIZE, "100").size()).isEqualTo(100);
    }

    /** size=1 是合法下界。 */
    @Test
    void size1ShouldBeAccepted() {
        assertThat(only(SIZE, "1").size()).isEqualTo(1);
    }

    /** sort=unit_price 合法。 */
    @Test
    void unitPriceSortShouldBeAccepted() {
        assertThat(only(SORT, "unit_price").sort()).isEqualTo(TaskSortField.UNIT_PRICE);
    }

    /** sort 白名单以外非法。 */
    @Test
    void unknownSortShouldBeRejected() {
        expectInvalid(() -> only(SORT, "created_at"), "sort");
    }

    /** sort 大写非法（只接受小写线上名）。 */
    @Test
    void uppercaseSortShouldBeRejected() {
        expectInvalid(() -> only(SORT, "PUBLISHED_AT"), "sort");
    }

    /** 驼峰 sort 非法。 */
    @Test
    void camelCaseSortShouldBeRejected() {
        expectInvalid(() -> only(SORT, "publishedAt"), "sort");
    }

    /** order=asc 合法。 */
    @Test
    void ascOrderShouldBeAccepted() {
        assertThat(only(ORDER, "asc").order()).isEqualTo(SortDirection.ASC);
    }

    /** order 大写非法。 */
    @Test
    void uppercaseOrderShouldBeRejected() {
        expectInvalid(() -> only(ORDER, "ASC"), "order");
    }

    /** order 白名单以外非法。 */
    @Test
    void unknownOrderShouldBeRejected() {
        expectInvalid(() -> only(ORDER, "newest"), "order");
    }

    // ==================== 辅助 ====================

    private static TaskSearchCriteria only(int index, String value) {
        String[] args = new String[11];
        args[index] = value;
        return parse(args);
    }

    private static TaskSearchCriteria pair(int first, String firstValue, int second, String secondValue) {
        String[] args = new String[11];
        args[first] = firstValue;
        args[second] = secondValue;
        return parse(args);
    }

    private static TaskSearchCriteria parse(String[] args) {
        return TaskSearchCriteria.from(args[0], args[1], args[2], args[3], args[4], args[5], args[6],
                args[7], args[8], args[9], args[10]);
    }

    private static ApiException expectInvalid(Executable action, String field) {
        ApiException ex = assertThrows(ApiException.class, action);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
        assertThat(fieldOf(ex)).as("data.fields 必须指明 %s", field).containsKey(field);
        return ex;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> fieldOf(ApiException ex) {
        Map<String, Object> data = (Map<String, Object>) ex.getData();
        return (Map<String, String>) data.get("fields");
    }
}
