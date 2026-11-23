package com.escrow.platform.identity.application;

import com.escrow.platform.identity.api.QualificationSubmitRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 雇主资质字段校验（v1-c5 §8 第 1 条，FR-ACCT.QUAL.01）。
 *
 * <p>纯单测：不启动 Spring、不连库，保证 MySQL 未启动时也能跑。</p>
 *
 * @since V1
 */
class QualificationValidatorTest {

    private final QualificationValidator validator = new QualificationValidator();

    private static QualificationSubmitRequest submit(String subjectType, String subjectName, String idLast4) {
        return new QualificationSubmitRequest(subjectType, subjectName, idLast4);
    }

    /** 合法三字段：无错误。 */
    @Test
    void validSubmissionShouldPass() {
        assertThat(validator.validateSubmit(submit("INDIVIDUAL", "张三", "1234"))).isEmpty();
    }

    /** 主体类型不在白名单：报 subjectType。 */
    @Test
    void unknownSubjectTypeShouldFail() {
        Map<String, String> fields = validator.validateSubmit(submit("COMPANY", "某某公司", "1234"));

        assertThat(fields).containsOnlyKeys("subjectType");
    }

    /** 主体类型大小写必须完全一致：小写不被接受。 */
    @Test
    void lowercaseSubjectTypeShouldFail() {
        Map<String, String> fields = validator.validateSubmit(submit("individual", "张三", "1234"));

        assertThat(fields).containsOnlyKeys("subjectType");
    }

    /** 主体类型为空：报 subjectType。 */
    @Test
    void blankSubjectTypeShouldFail() {
        Map<String, String> fields = validator.validateSubmit(submit("  ", "张三", "1234"));

        assertThat(fields).containsKey("subjectType");
    }

    /** 主体名称为空：报 subjectName。 */
    @Test
    void blankSubjectNameShouldFail() {
        Map<String, String> fields = validator.validateSubmit(submit("INDIVIDUAL", "   ", "1234"));

        assertThat(fields).containsOnlyKeys("subjectName");
    }

    /** 主体名称 129 字符：超出 VARCHAR(128)。 */
    @Test
    void tooLongSubjectNameShouldFail() {
        Map<String, String> fields = validator.validateSubmit(
                submit("INDIVIDUAL", "名".repeat(129), "1234"));

        assertThat(fields).containsOnlyKeys("subjectName");
    }

    /** 主体名称恰好 128 字符：边界内，通过。 */
    @Test
    void subjectNameAtMaxLengthShouldPass() {
        assertThat(validator.validateSubmit(submit("INDIVIDUAL", "名".repeat(128), "1234"))).isEmpty();
    }

    /** 主体名称带前后空格：按 trim 后判断，合法（服务层负责存 trim 后的值）。 */
    @Test
    void paddedSubjectNameShouldPass() {
        assertThat(validator.validateSubmit(submit("SOLE_PROPRIETOR", "  小王便利店  ", "1234"))).isEmpty();
    }

    /** 证件后 4 位含非数字：报 idLast4。 */
    @Test
    void nonNumericIdLast4ShouldFail() {
        Map<String, String> fields = validator.validateSubmit(submit("INDIVIDUAL", "张三", "12a4"));

        assertThat(fields).containsOnlyKeys("idLast4");
    }

    /** 证件后 4 位只有 3 位：报 idLast4。 */
    @Test
    void threeDigitIdLast4ShouldFail() {
        Map<String, String> fields = validator.validateSubmit(submit("INDIVIDUAL", "张三", "123"));

        assertThat(fields).containsOnlyKeys("idLast4");
    }

    /** 证件后 4 位有 5 位：报 idLast4。 */
    @Test
    void fiveDigitIdLast4ShouldFail() {
        Map<String, String> fields = validator.validateSubmit(submit("INDIVIDUAL", "张三", "12345"));

        assertThat(fields).containsOnlyKeys("idLast4");
    }

    /** 三个字段全错：一次返回三个键，便于前端一次性提示。 */
    @Test
    void multipleInvalidFieldsShouldAllBeReported() {
        Map<String, String> fields = validator.validateSubmit(submit(null, null, null));

        assertThat(fields).containsOnlyKeys("subjectType", "subjectName", "idLast4");
    }
}
