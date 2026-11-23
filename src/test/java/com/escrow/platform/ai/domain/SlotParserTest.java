package com.escrow.platform.ai.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则槽位解析（V12.1 验收 P3）：技能词族、金额碎片、未识别技能的置信度。
 *
 * @since V12.1
 */
class SlotParserTest {

    private final SlotParser parser = new SlotParser();

    /** 「展会布展」必须进技能槽，且「500以内」不能在关键词里留下 50/00 碎片。 */
    @Test
    void v12c1RecognizesExhibitionSkillWithoutDigitFragments() {
        QuerySlots slots = parser.parse("上海周末能接的展会布展，报酬500以内");

        assertThat(slots.skill()).isEqualTo("布展");
        assertThat(slots.priceMax()).isEqualTo(50000L);
        assertThat(slots.location()).isEqualTo("上海");
        assertThat(slots.weekend()).isTrue();
        assertThat(slots.keywords()).noneMatch(keyword -> keyword.matches(".*\\d.*"));
        assertThat(slots.keywords()).doesNotContain("报酬", "以内");
        assertThat(slots.confidence()).isGreaterThanOrEqualTo(80);
    }

    /** 未识别技能：即使地点/预算/周末都命中，置信度也不超过 60。 */
    @Test
    void v12c1UnknownSkillCapsConfidence() {
        QuerySlots slots = parser.parse("帮我找个能干活的人，上海，预算 500 以内，周末");

        assertThat(slots.skill()).isNull();
        assertThat(slots.priceMax()).isEqualTo(50000L);
        assertThat(slots.location()).isEqualTo("上海");
        assertThat(slots.confidence()).isEqualTo(60);
    }

    /** 识别出技能时保留更高置信度，不受上限影响。 */
    @Test
    void v12c1KnownSkillKeepsHigherConfidence() {
        QuerySlots slots = parser.parse("短视频剪辑，北京，预算 500 以内，周末");

        assertThat(slots.skill()).isEqualTo("短视频剪辑");
        assertThat(slots.confidence()).isGreaterThan(60);
    }

    /** 「500元以下」的金额整段被抽走，关键词只剩有效信号。 */
    @Test
    void v12c1PricePhraseLeavesNoFragments() {
        QuerySlots slots = parser.parse("需要视频剪辑，预算 800 元以下");

        assertThat(slots.priceMax()).isEqualTo(80000L);
        assertThat(slots.keywords()).noneMatch(keyword -> keyword.matches(".*\\d.*"));
    }
}
