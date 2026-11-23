package com.escrow.platform.dispute.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 争议证据，对应表 {@code dispute_evidence}（V15 建）。
 *
 * <p>图片以 JSON 数组字符串落库（{@code image_urls}），限制 ≤9 张在服务层校验。</p>
 *
 * @since V6
 */
@Getter
@Setter
@TableName("dispute_evidence")
public class DisputeEvidence {

    /** 雪花 ID。 */
    @TableId
    private Long id;

    /** 争议单 ID。 */
    private Long disputeId;

    /** 提交人账号 ID。 */
    private Long submitterId;

    /** 提交方，与争议发起方同一枚举。 */
    private DisputeInitiatorType submitterType;

    /** 文字证据；无文字时为空。 */
    private String content;

    /** 图片 URL 的 JSON 数组文本；无图片时为空。 */
    private String imageUrls;

    /** 提交时间。 */
    private LocalDateTime createdAt;
}
