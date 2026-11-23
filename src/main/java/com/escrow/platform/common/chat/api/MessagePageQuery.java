package com.escrow.platform.common.chat.api;

/**
 * 历史消息查询参数（V11，补遗 §4 {@code GET /api/conversations/{id}/messages}）。
 *
 * <p>两种翻页方式互斥：带 {@code afterId} 走增量轮询（只看新消息），否则走页码分页。
 * 页大小由服务层夹到 1~50，前端传更大值不会放大查询量。</p>
 *
 * @param page    页码，从 1 开始；{@code afterId} 非空时忽略
 * @param size    每页条数；{@code afterId} 非空时作为增量上限
 * @param afterId 只取 ID 大于该值的消息，用于轮询
 * @since V11
 */
public record MessagePageQuery(Integer page, Integer size, Long afterId) {
}
