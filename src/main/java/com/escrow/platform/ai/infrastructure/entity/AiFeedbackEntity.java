package com.escrow.platform.ai.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 检索反馈（V10-c6/c7，表 {@code ai_feedback}）。
 *
 * <p>唯一键 {@code (trace_no, user_id)} 是幂等的结构保证：重复上报不累加，
 * 只把布尔标记置位（D7 裁决）。</p>
 *
 * @since V10
 */
@Getter
@Setter
@TableName("ai_feedback")
public class AiFeedbackEntity {

    @TableId
    private Long id;

    private String traceNo;

    private Long userId;

    private Boolean clicked;

    /** 60 秒窗口内的二次搜索；超窗口不记。 */
    private Boolean secondSearch;

    private LocalDateTime updatedAt;
}
