package com.escrow.platform.task.domain;

import java.util.Objects;

/**
 * 草稿编辑策略（SRS FR-TASK.CREATE.02 / .05），纯静态函数、无状态。
 *
 * <p>两条规则不在 Service 里内联：价格锁与时间校验是发布语义的一部分，
 * v2-c5 发布时还要复用同一套判据，收口在一个类里才不会漂移。</p>
 *
 * @since V2
 */
public final class TaskEditPolicy {

    private TaskEditPolicy() {
    }

    /**
     * 已发布任务是否改了单价或名额数（FR-TASK.CREATE.05）。
     *
     * <p>为 true 时调用方抛 409 {@code TASK_PRICE_LOCKED}（本卡新增的唯一业务码）；
     * 其余字段允许修改且不影响已托管金额。</p>
     *
     * @param published 任务是否已发布（published_at 非空）
     * @param oldPrice  库里的单价
     * @param oldCount  库里的名额数
     * @param newPrice  请求的单价
     * @param newCount  请求的名额数
     * @return 已发布且（单价或名额数）发生变化
     */
    public static boolean priceOrSlotChanged(boolean published, Long oldPrice, Integer oldCount,
                                             Long newPrice, Integer newCount) {
        return published && (!Objects.equals(oldPrice, newPrice)
                || !Objects.equals(oldCount, newCount));
    }

    /**
     * 是否要求开始时间不早于当前时间（FR-TASK.CREATE.02）。
     *
     * <p>只有草稿要求：草稿还没开始，开始时间在过去没有意义。
     * 已发布任务编辑只校验 {@code end_time > start_time}（卡片 §1.3 第 9 条）——
     * 任务可能早已开始，FR-TASK.CREATE.05 也明确其余字段允许修改。</p>
     *
     * @param published 任务是否已发布
     * @return 草稿返回 true
     */
    public static boolean requireFutureStart(boolean published) {
        return !published;
    }
}
