package com.escrow.platform.engagement.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 签约评价，对应表 {@code engagement_review}（V18）。
 *
 * <p>一份签约每人只能评一次，由 {@code uk_engagement_review_contract_reviewer} 兜底。
 * {@code revieweeId} 是刻意的冗余：信誉页按被评价人聚合，不必回查任务表找雇主。</p>
 *
 * @since V7
 */
@Getter
@Setter
@TableName("engagement_review")
public class EngagementReview {

    /** 雪花 ID。 */
    @TableId
    private Long id;

    /** 签约 ID。 */
    private Long contractId;

    /** 评价人账号 ID。 */
    private Long reviewerId;

    /** 被评价人账号 ID。 */
    private Long revieweeId;

    /** 评分 1~5。 */
    private Integer rating;

    /** 文字评价，可空。 */
    private String comment;

    /** 创建时间。 */
    private LocalDateTime createdAt;
}
