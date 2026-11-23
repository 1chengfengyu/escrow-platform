package com.escrow.platform.engagement.api;

/**
 * 报名创建结果（FR-SIGN.APPLY.01）。
 *
 * <p>只回 {@code id}：状态恒为待处理，不需要让客户端再读一次；
 * {@code Long} 由全局 {@code JacksonConfig} 序列化成字符串，防 JS 末位抹 0。</p>
 *
 * @param id 报名记录 ID
 * @since V3
 */
public record ApplicationCreatedView(Long id) {
}
