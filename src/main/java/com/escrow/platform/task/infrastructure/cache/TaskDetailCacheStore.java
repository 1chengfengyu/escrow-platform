package com.escrow.platform.task.infrastructure.cache;

/**
 * 任务详情缓存的一层存储（v2-c4，docs/decisions.md #49）。
 *
 * <p>抽象出这一层是为了让 {@link TaskDetailCache} 只负责编排三级读取、单飞与降级，
 * 而把「本地 / Redis」两种介质隔离在实现里。测试用内存伪实现注入，
 * 不需要真 Redis（卡片 §4.6 / §6）。</p>
 *
 * <p>清理方法命名为 {@code invalidate} 而不是 evict：全项目只有
 * {@code TaskDraftService.update} 一个业务驱逐入口，命名区分开便于审计调用点唯一性。</p>
 *
 * @since V2
 */
public interface TaskDetailCacheStore {

    /**
     * 读取该任务的缓存项。
     *
     * @param taskId 任务 ID
     * @return 命中快照 / 命中空值标记 / 未命中三态
     */
    Lookup get(Long taskId);

    /**
     * 写入任务快照。
     *
     * @param taskId     任务 ID
     * @param snapshot   快照
     * @param ttlSeconds 存活秒数（已含抖动）
     */
    void put(Long taskId, TaskDetailSnapshot snapshot, long ttlSeconds);

    /**
     * 写入「记录不存在」空值标记（仅 Redis 层使用）。
     *
     * @param taskId     任务 ID
     * @param ttlSeconds 存活秒数（已含抖动）
     */
    void putMissing(Long taskId, long ttlSeconds);

    /**
     * 清除该任务的缓存项，含空值标记。
     *
     * @param taskId 任务 ID
     */
    void invalidate(Long taskId);

    /**
     * 单层读取结果。
     *
     * @param hit      是否命中缓存项（快照或空值标记）
     * @param missing  命中项是否为空值标记
     * @param snapshot 命中时的快照；空值标记或未命中时为 {@code null}
     */
    record Lookup(boolean hit, boolean missing, TaskDetailSnapshot snapshot) {

        public static Lookup miss() {
            return new Lookup(false, false, null);
        }

        public static Lookup nullMarker() {
            return new Lookup(true, true, null);
        }

        public static Lookup present(TaskDetailSnapshot snapshot) {
            return new Lookup(true, false, snapshot);
        }
    }
}
