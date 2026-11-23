package com.escrow.platform.engagement.api;

import com.escrow.platform.engagement.domain.ApplicationStatus;

import java.time.LocalDateTime;

/**
 * 报名者列表项（FR-SIGN.APPLY.07、FR-ACCT.PROFILE.02）。
 *
 * <p>只暴露公开级字段：昵称、报名时间、报名状态。真实姓名、联系方式、证件后 4 位、
 * 简历附件一律不进入本视图；雇主与劳动者签约后才可看简历（FR-ACCT.PROFILE.03，不在本卡）。</p>
 *
 * @param id             报名记录 ID
 * @param workerId       劳动者用户 ID
 * @param workerNickname 劳动者昵称（公开资料）
 * @param status         报名状态
 * @param appliedAt      报名时间
 * @since V3
 */
public record ApplicationListItemView(Long id, Long workerId, String workerNickname,
                                      ApplicationStatus status, LocalDateTime appliedAt) {
}
