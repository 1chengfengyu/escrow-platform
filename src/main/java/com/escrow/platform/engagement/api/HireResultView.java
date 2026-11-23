package com.escrow.platform.engagement.api;

/**
 * 录用结果（FR-SIGN.HIRE.01）。
 *
 * <p>只回签约 ID：报名、名额状态由列表 / 任务详情自行反映，不在本视图重复。
 * 雪花 ID 由全局 Jackson 配置序列化为字符串，避免前端 JS 精度丢失。</p>
 *
 * @param contractId 签约 ID
 * @since V3
 */
public record HireResultView(Long contractId) {
}
