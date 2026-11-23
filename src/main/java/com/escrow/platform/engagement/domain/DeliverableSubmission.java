package com.escrow.platform.engagement.domain;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;

import java.util.List;
import java.util.Map;

/**
 * 交付物提交内容（FR-DELIVER.SUBMIT.01）。
 *
 * <p>只做「数量 / 链接形态 / 说明长度」校验；单张 ≤10MB 与 JPG/PNG 由上传端保证——
 * 本系统只收 URL，服务端拿不到字节，无法在此校验（v3-c4 卡 D5）。</p>
 *
 * @param imageUrls   图片 URL 列表，1~9 个，均为 http(s) 链接
 * @param description 文字说明，≤500 字；空白按「无说明」归一成 {@code null}
 * @since V3
 */
public record DeliverableSubmission(List<String> imageUrls, String description) {

    private static final String INVALID_MESSAGE = "请求参数不合法";
    private static final int MIN_IMAGES = 1;
    private static final int MAX_IMAGES = 9;
    private static final int MAX_URL_LENGTH = 500;
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    /**
     * 校验并归一提交内容。
     *
     * @throws ApiException 任一字段不合法，{@code code=INVALID_PARAMETER}，
     *                      {@code data.fields} 指明字段与原因
     */
    public static DeliverableSubmission from(List<String> images, String description) {
        if (images == null || images.size() < MIN_IMAGES || images.size() > MAX_IMAGES) {
            throw invalid("images", "必须提交 " + MIN_IMAGES + "~" + MAX_IMAGES + " 张图片");
        }
        List<String> normalizedImages = images.stream().map(DeliverableSubmission::validUrl).toList();

        String normalizedDescription = description == null ? null : description.trim();
        if (normalizedDescription != null && normalizedDescription.isEmpty()) {
            normalizedDescription = null;
        }
        if (normalizedDescription != null && normalizedDescription.length() > MAX_DESCRIPTION_LENGTH) {
            throw invalid("description", "文字说明不能超过 " + MAX_DESCRIPTION_LENGTH + " 字");
        }
        return new DeliverableSubmission(normalizedImages, normalizedDescription);
    }

    private static String validUrl(String image) {
        if (image == null || image.isBlank()) {
            throw invalid("images", "图片地址不能为空");
        }
        String trimmed = image.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw invalid("images", "图片地址必须是 http(s) 链接");
        }
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw invalid("images", "单个图片地址不能超过 " + MAX_URL_LENGTH + " 字符");
        }
        return trimmed;
    }

    private static ApiException invalid(String field, String reason) {
        return new ApiException(ErrorCode.INVALID_PARAMETER, INVALID_MESSAGE,
                Map.of("fields", Map.of(field, reason)));
    }
}
