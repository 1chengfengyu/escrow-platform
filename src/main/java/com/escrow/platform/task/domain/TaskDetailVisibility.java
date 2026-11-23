package com.escrow.platform.task.domain;

import java.time.LocalDateTime;

/**
 * 任务详情可见性判定（FR-TASK.DETAIL.01 / .02，docs/decisions.md #48）。
 *
 * <p>纯函数：零 Spring、零 MyBatis、零时间调用（不取 {@code now}），单测直接传值。</p>
 *
 * @since V2
 */
public final class TaskDetailVisibility {

    private TaskDetailVisibility() {
    }

    /**
     * 是否当事人（FR-TASK.DETAIL.02）。
     *
     * @param employerId    任务雇主
     * @param viewerId      观察者；未登录为 null
     * @param viewerHasSlot 观察者是否持有该任务的名额（c3 恒 false；c4 接入报名表后传真值）
     */
    public static boolean isParty(Long employerId, Long viewerId, boolean viewerHasSlot) {
        if (viewerId == null) {
            return false;
        }
        return viewerId.equals(employerId) || viewerHasSlot;
    }

    /** 是否可见：已发布对所有人可见；草稿只对当事人可见。 */
    public static boolean isVisibleTo(LocalDateTime publishedAt, boolean party) {
        return publishedAt != null || party;
    }
}
