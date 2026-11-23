package com.escrow.platform.task.domain;

import java.util.Collection;

/**
 * 任务域查询「未决争议数」的只读端口（FR-TASK.DETAIL.02，R2 争议中）。
 *
 * <p>为什么用端口而不是直接注入争议域 Service：争议域的 {@code DisputeService}
 * 本身要调用任务域查询任务与名额，任务域若反向注入它就会形成构造器循环依赖。
 * 端口放在本域，由争议域实现，依赖方向仍是「争议 → 任务」，不产生环。</p>
 *
 * @since V7（修复 V4 起 openDisputeCount 恒为 0 的缺陷）
 */
public interface OpenDisputeQuery {

    /**
     * 统计一批名额上处于未决状态（{@code DISPUTE_PENDING}）的争议数量。
     *
     * @param slotIds 名额 ID 集合；为空时返回 0
     * @return 未决争议数
     */
    int countOpen(Collection<Long> slotIds);
}
