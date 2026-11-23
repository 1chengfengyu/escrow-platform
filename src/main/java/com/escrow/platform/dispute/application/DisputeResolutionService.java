package com.escrow.platform.dispute.application;

import com.escrow.platform.dispute.domain.DisputeResolution;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.settlement.application.PayoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 争议编排（非事务，V6）：事务内推进状态与资金，事务提交后再触发 T2 出站。
 *
 * <p>与验收编排同构：T2 的通道调用必须在事务外（SRS A.4 T1/T2/T3），
 * 所以拆成两个 Bean，避免同类自调用让 {@code @Transactional} 失效。</p>
 *
 * @since V6
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeResolutionService {

    private final DisputeTxService disputeTxService;
    private final PayoutService payoutService;

    /**
     * 劳动者申诉开争议。
     *
     * @param current    会话主体
     * @param contractId 签约 ID
     * @param reason     申诉理由
     * @return 争议单 ID
     */
    public Long appeal(LoginUser current, Long contractId, String reason) {
        return disputeTxService.openWorkerAppeal(current, contractId, reason);
    }

    /**
     * 撤回争议。
     *
     * @param current   会话主体
     * @param disputeId 争议单 ID
     */
    public void withdraw(LoginUser current, Long disputeId) {
        disputeTxService.withdraw(current, disputeId);
    }

    /**
     * 管理员裁决；放款时在事务提交后触发打款出站。
     *
     * @param current    会话主体
     * @param disputeId  争议单 ID
     * @param resolution 裁决结论
     * @param basis      裁决依据
     * @return 裁决结果
     */
    public DisputeTxService.ResolutionOutcome resolve(LoginUser current, Long disputeId,
                                                      DisputeResolution resolution, String basis) {
        DisputeTxService.ResolutionOutcome outcome =
                disputeTxService.resolve(current, disputeId, resolution, basis);
        if (outcome.payoutId() != null) {
            payoutService.dispatch(outcome.payoutId());
        }
        return outcome;
    }
}
