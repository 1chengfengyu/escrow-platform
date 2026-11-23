package com.escrow.platform.dispute.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.dispute.domain.Dispute;
import com.escrow.platform.dispute.domain.DisputeStatus;
import com.escrow.platform.dispute.infrastructure.mapper.DisputeMapper;
import com.escrow.platform.task.domain.OpenDisputeQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;

/**
 * 未决争议计数的只读实现，供任务详情聚合 R2「争议中」使用（FR-TASK.DETAIL.02）。
 *
 * <p>刻意不依赖 {@code TaskQueryService}：{@code DisputeService} 已经依赖任务域，
 * 若本类再反向依赖任务域就会与任务详情形成循环依赖。</p>
 *
 * @since V7
 */
@Service
@RequiredArgsConstructor
public class DisputeQueryService implements OpenDisputeQuery {

    private final DisputeMapper disputeMapper;

    @Override
    public int countOpen(Collection<Long> slotIds) {
        if (slotIds == null || slotIds.isEmpty()) {
            return 0;
        }
        Long count = disputeMapper.selectCount(new LambdaQueryWrapper<Dispute>()
                .in(Dispute::getSlotId, slotIds)
                .eq(Dispute::getStatus, DisputeStatus.DISPUTE_PENDING));
        return count == null ? 0 : count.intValue();
    }
}
