package com.escrow.platform.engagement.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 报名状态（SRS A.3.7「报名单」、FR-SIGN.APPLY.06）。
 *
 * <p>落库值为枚举名，与 {@code V7__v3_engagement_application.sql} 的
 * {@code ck_engagement_application_status} 白名单逐字一致——生成列
 * {@code active_task_id / active_worker_id} 也按 {@link #isActive()} 的两个取值判空。</p>
 *
 * <p>状态迁移（录用 / 拒绝 / 撤回）归 v3-c2 / v3-c3；本卡只落枚举与终态判定，不写状态机。</p>
 *
 * @since V3
 */
public enum ApplicationStatus {

    /** 待处理：报名已提交，等待雇主处理。 */
    APPLY_PENDING,

    /** 已录用：雇主已录用，名额转入已占用。 */
    APPLY_HIRED,

    /** 已拒绝：名额已满 / 任务撤单 / 已过结束时间（终态）。 */
    APPLY_REJECTED,

    /** 已撤回：劳动者主动撤回或取消签约（终态）。 */
    APPLY_WITHDRAWN;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }

    /** 终态：已拒绝 / 已撤回不可再迁出；再次报名必须新建记录（A.3.7 尾注）。 */
    public boolean isTerminal() {
        return this == APPLY_REJECTED || this == APPLY_WITHDRAWN;
    }

    /** 有效报名：与生成列的唯一索引口径一致（待处理 / 已录用）。 */
    public boolean isActive() {
        return this == APPLY_PENDING || this == APPLY_HIRED;
    }
}
