package com.escrow.platform.common.redis;

/**
 * 一次限流判定的结果（NFR-SEC.06）。
 *
 * @param allowed           是否放行：本窗口内计数未超过上限
 * @param count             本窗口内累计请求数（含本次）
 * @param limit             上限
 * @param retryAfterSeconds 建议重试秒数，落在 {@code (0, window]}；放行时同样给出，供调用方记录
 * @since V1
 */
public record RateLimitResult(boolean allowed, long count, int limit, long retryAfterSeconds) {
}
