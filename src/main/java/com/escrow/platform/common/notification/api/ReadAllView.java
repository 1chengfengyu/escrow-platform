package com.escrow.platform.common.notification.api;

/**
 * 全部标记已读的结果（V7，FR-MSG.LIST.01）。
 *
 * @param updated 本次实际标记的条数
 * @since V7
 */
public record ReadAllView(int updated) {
}
