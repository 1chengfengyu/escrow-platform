package com.escrow.platform.task.api;

/**
 * 任务草稿创建成功视图（FR-TASK.CREATE.01）。
 *
 * <p>全局 {@code Long} 字符串化（docs/decisions.md #14）：雪花 ID 是 19 位，超过 JS
 * {@code Number.MAX_SAFE_INTEGER}，JSON 里 {@code id} 是字符串，防止末位被抹成 0。</p>
 *
 * @param id 草稿任务 ID
 * @since V2
 */
public record TaskCreatedView(Long id) {
}
