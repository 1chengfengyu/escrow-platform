package com.escrow.platform.engagement.domain;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 交付物提交内容校验（v3-c4，FR-DELIVER.SUBMIT.01）。
 *
 * <p>只锁服务端能校验的三件事：1~9 张、http(s) 链接、说明 ≤500 字；
 * 单张大小与格式由上传端保证，不在本层（D5）。</p>
 *
 * @since V3
 */
class DeliverableSubmissionTest {

    @Test
    void validImagesAndDescriptionShouldPass() {
        DeliverableSubmission submission = DeliverableSubmission.from(
                List.of("https://img.example.com/1.jpg", "http://img.example.com/2.png"), " 已完成 ");

        assertThat(submission.imageUrls()).containsExactly(
                "https://img.example.com/1.jpg", "http://img.example.com/2.png");
        assertThat(submission.description()).isEqualTo("已完成");
    }

    @Test
    void nullOrEmptyOrTooManyImagesShouldBeRejected() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            tooMany.add("https://img.example.com/" + i + ".jpg");
        }
        List<List<String>> invalidImages = List.of(Collections.emptyList(), tooMany);
        for (List<String> images : invalidImages) {
            assertInvalid("images", () -> DeliverableSubmission.from(images, null));
        }
        assertInvalid("images", () -> DeliverableSubmission.from(null, null));
    }

    @Test
    void nineImagesShouldBeAccepted() {
        List<String> images = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            images.add("https://img.example.com/" + i + ".jpg");
        }

        assertThat(DeliverableSubmission.from(images, null).imageUrls()).hasSize(9);
    }

    @Test
    void blankOrNonHttpUrlShouldBeRejected() {
        assertInvalid("images", () -> DeliverableSubmission.from(List.of(" "), null));
        assertInvalid("images", () -> DeliverableSubmission.from(List.of("img.example.com/1.jpg"), null));
        assertInvalid("images", () -> DeliverableSubmission.from(List.of("ftp://img.example.com/1.jpg"), null));
    }

    @Test
    void tooLongUrlShouldBeRejected() {
        String tooLong = "https://img.example.com/" + "a".repeat(500);

        assertInvalid("images", () -> DeliverableSubmission.from(List.of(tooLong), null));
    }

    @Test
    void blankDescriptionShouldNormalizeToNull() {
        assertThat(DeliverableSubmission.from(List.of("https://img.example.com/1.jpg"), "   ")
                .description()).isNull();
        assertThat(DeliverableSubmission.from(List.of("https://img.example.com/1.jpg"), null)
                .description()).isNull();
    }

    @Test
    void tooLongDescriptionShouldBeRejected() {
        assertInvalid("description", () -> DeliverableSubmission.from(
                List.of("https://img.example.com/1.jpg"), "说".repeat(501)));
    }

    private static void assertInvalid(String field, Runnable action) {
        ApiException ex = catchThrowableOfType(() -> {
            action.run();
            throw new AssertionError("应校验失败");
        }, ApiException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
        assertThat(ex.getData()).asString().contains(field);
    }
}
