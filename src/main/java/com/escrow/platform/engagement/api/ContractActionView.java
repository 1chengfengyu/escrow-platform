package com.escrow.platform.engagement.api;

import com.escrow.platform.engagement.domain.ContractStatus;

/**
 * 签约动作结果（开始履约 / 取消签约）。
 *
 * @param contractId 签约 ID（雪花，JSON 按字符串输出）
 * @param status     动作完成后的签约状态
 * @since V3
 */
public record ContractActionView(Long contractId, ContractStatus status) {
}
