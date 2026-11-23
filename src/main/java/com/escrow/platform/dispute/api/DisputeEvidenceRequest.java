package com.escrow.platform.dispute.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 提交争议证据的请求（V6，FR-DISPUTE.EVIDENCE.01）。
 *
 * <p>图片 ≤9 张、文字 ≤1000 字，至少有一项非空；「至少一项」无法用单字段注解表达，
 * 由服务层做合并校验。</p>
 *
 * @param content   文字证据
 * @param imageUrls 图片 URL 列表
 * @since V6
 */
public record DisputeEvidenceRequest(
        @Size(max = 1000, message = "content 最长 1000 字") String content,
        @Size(max = 9, message = "imageUrls 最多 9 张")
        List<@NotBlank(message = "图片 URL 不能为空")
             @Size(max = 500, message = "图片 URL 最长 500 字") String> imageUrls) {
}
