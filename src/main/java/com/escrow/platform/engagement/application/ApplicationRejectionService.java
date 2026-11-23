package com.escrow.platform.engagement.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.engagement.domain.ApplicationStateMachine;
import com.escrow.platform.engagement.domain.ApplicationStatus;
import com.escrow.platform.engagement.domain.EngagementApplication;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementApplicationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 待处理报名的统一拒绝入口（FR-SIGN.APPLY.08）。
 *
 * <p>三条触发路径共用本方法，禁止各写一份判定：</p>
 * <ol>
 *   <li>满员——录用事务内，占名额后已无「待占用」名额时调用；</li>
 *   <li>撤单——{@code TaskCancelService} 释放名额后调用；</li>
 *   <li>到期——{@code TaskExpiryService} 收口后调用。</li>
 * </ol>
 *
 * <p>条件更新只命中 {@code APPLY_PENDING}，已录用 / 已拒绝 / 已撤回不受影响；
 * 目标状态由 {@link ApplicationStateMachine} 给出，不在这里写状态常量。</p>
 *
 * @since V3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationRejectionService {

    private final EngagementApplicationMapper applicationMapper;

    /**
     * 把某任务下全部「待处理」报名置为「已拒绝」。
     *
     * @param taskId 任务 ID
     * @return 本次实际拒绝的报名条数（0 表示没有待处理报名，属正常情况）
     */
    @Transactional
    public int rejectPendingOfTask(Long taskId) {
        ApplicationStatus rejected = ApplicationStateMachine.transit(ApplicationStatus.APPLY_PENDING,
                ApplicationStateMachine.Event.REJECT);
        int updated = applicationMapper.update(null, new LambdaUpdateWrapper<EngagementApplication>()
                .eq(EngagementApplication::getTaskId, taskId)
                .eq(EngagementApplication::getStatus, ApplicationStatus.APPLY_PENDING)
                .set(EngagementApplication::getStatus, rejected));
        if (updated > 0) {
            log.info("批量拒绝待处理报名 taskId={} rejected={}", taskId, updated);
        }
        return updated;
    }
}
