package com.escrow.platform.settlement.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.settlement.api.PayoutAdminView;
import com.escrow.platform.settlement.api.PayoutAgingAlert;
import com.escrow.platform.settlement.api.PayoutDecisionRequest;
import com.escrow.platform.settlement.domain.PayoutManualDecision;
import com.escrow.platform.settlement.domain.PayoutManualDecisionType;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutManualDecisionMapper;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 打款处置台与账龄告警（V5，FR-PAY.MANUAL.01~.03 / FR-JOB.AGING.01）。
 *
 * <p>权限口径与资质审核一致：<b>只认会话里的当前身份</b>，
 * 不接受请求参数覆盖（{@code QualificationReviewService#requireAdmin} 同款）。</p>
 *
 * <p>三选一的语义：</p>
 * <ul>
 *   <li>{@code CONFIRM_ACTUAL}：按通道实际金额完成结算，差额退回雇主；</li>
 *   <li>{@code ZERO_PAYMENT}：零扣款完成结算，除服务费外全部退回雇主，必须填凭据说明；</li>
 *   <li>{@code TO_DIFFERENCE}：只记录处置并发起差异（差异单由 V6 对账生成），状态不变。</li>
 * </ul>
 *
 * @since V5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutAdminService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int AGING_LIMIT = 100;
    private static final String ADMIN_ONLY_MESSAGE = "仅管理员可处置打款单";
    private static final String STATUS_PARAM_MESSAGE =
            "status 只允许 PAYOUT_PENDING_EXECUTE / PAYOUT_EXECUTING / PAYOUT_PENDING_VERIFICATION / "
                    + "PAYOUT_MANUAL_REVIEW / PAYOUT_SUCCESS / PAYOUT_FAIL";

    private final PayoutOrderMapper payoutOrderMapper;
    private final PayoutManualDecisionMapper payoutManualDecisionMapper;
    private final PayoutTxService payoutTxService;
    private final PayoutRuntimeConfig runtimeConfig;
    private final AuditRecorder auditRecorder;

    /**
     * 分页查询打款单（可按状态过滤）。
     *
     * @param current 当前登录用户
     * @param status  状态过滤，空表示全部
     * @param page    页码，从 1 开始
     * @param size    每页条数（1~{@value #MAX_PAGE_SIZE}）
     * @return 分页视图
     */
    public PageView<PayoutAdminView> list(LoginUser current, String status, int page, int size) {
        requireAdmin(current);
        int pageNo = Math.max(1, page);
        int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        LambdaQueryWrapper<PayoutOrder> wrapper = new LambdaQueryWrapper<PayoutOrder>()
                .orderByAsc(PayoutOrder::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(PayoutOrder::getStatus, parseStatus(status));
        }
        Page<PayoutOrder> result = payoutOrderMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        List<PayoutAdminView> items = result.getRecords().stream().map(PayoutAdminView::of).toList();
        return PageView.of(items, pageNo, pageSize, result.getTotal());
    }

    /**
     * 账龄告警：待查证 / 待人工超过阈值的单据。
     *
     * @param current 当前登录用户
     * @return 告警列表，按账龄降序
     */
    public List<PayoutAgingAlert> aging(LoginUser current) {
        requireAdmin(current);
        return agingForSystem();
    }

    /**
     * 系统内查询账龄告警（定时任务用，不做 HTTP 权限校验）。
     *
     * @return 告警列表，按账龄降序
     */
    public List<PayoutAgingAlert> agingForSystem() {
        LocalDateTime cutoff = runtimeConfig.agingCutoff();
        List<PayoutOrder> orders = payoutOrderMapper.selectList(
                new LambdaQueryWrapper<PayoutOrder>()
                        .in(PayoutOrder::getStatus, PayoutStatus.PAYOUT_PENDING_VERIFICATION,
                                PayoutStatus.PAYOUT_MANUAL_REVIEW)
                        .le(PayoutOrder::getStatusEnteredAt, cutoff)
                        .orderByAsc(PayoutOrder::getStatusEnteredAt)
                        .last("LIMIT " + AGING_LIMIT));
        return orders.stream().map(order -> new PayoutAgingAlert(
                order.getId(), order.getSettlementId(), order.getRequestNo(), order.getStatus(),
                order.getAmount(), order.getStatusEnteredAt(),
                Math.max(0L, Duration.between(order.getStatusEnteredAt(), LocalDateTime.now()).toMinutes())
        )).toList();
    }

    /**
     * 待人工处理打款单数量（V7 治理看板用，只读，不做 HTTP 权限校验）。
     *
     * @return {@code status = PAYOUT_MANUAL_REVIEW} 的行数
     */
    public long countManualReview() {
        Long count = payoutOrderMapper.selectCount(new LambdaQueryWrapper<PayoutOrder>()
                .eq(PayoutOrder::getStatus, PayoutStatus.PAYOUT_MANUAL_REVIEW));
        return count == null ? 0L : count;
    }

    /**
     * 人工处置一张待人工打款单。
     *
     * @param current 当前登录用户（必须是管理员身份）
     * @param payoutId 打款单 ID
     * @param request 处置请求
     * @return 处置后的打款单视图
     */
    @Transactional
    public PayoutAdminView decide(LoginUser current, Long payoutId, PayoutDecisionRequest request) {
        requireAdmin(current);
        PayoutOrder payout = payoutOrderMapper.selectById(payoutId);
        if (payout == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "打款单不存在");
        }
        if (payout.getStatus() != PayoutStatus.PAYOUT_MANUAL_REVIEW) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "打款单不在待人工状态");
        }
        long actual = switch (request.decision()) {
            case CONFIRM_ACTUAL -> requireActualAmount(payout, request.actualAmount());
            case ZERO_PAYMENT -> requireZeroPayment(request.remark());
            case TO_DIFFERENCE -> -1L;
        };

        PayoutManualDecision decision = new PayoutManualDecision();
        decision.setId(IdWorker.getId());
        decision.setPayoutId(payoutId);
        decision.setDecision(request.decision());
        decision.setActualAmount(actual < 0 ? null : actual);
        decision.setOperatorId(current.userId());
        decision.setRemark(request.remark());
        decision.setCreatedAt(LocalDateTime.now());
        payoutManualDecisionMapper.insert(decision);

        if (request.decision() != PayoutManualDecisionType.TO_DIFFERENCE) {
            payoutTxService.handleManualConfirm(payoutId, actual);
        }
        log.warn("人工处置打款单 payoutId={} decision={} actual={} operator={}",
                payoutId, request.decision(), actual, current.userId());
        PayoutOrder updated = payoutOrderMapper.selectById(payoutId);
        auditRecorder.record("PAYOUT", String.valueOf(payoutId), request.decision().name(),
                PayoutStatus.PAYOUT_MANUAL_REVIEW.name(), updated.getStatus().name(),
                "actual=" + (actual < 0 ? "N/A" : actual) + " remark=" + request.remark());
        return PayoutAdminView.of(updated);
    }

    private static void requireAdmin(LoginUser current) {
        if (current == null || current.currentIdentity() != IdentityType.ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN, ADMIN_ONLY_MESSAGE);
        }
    }

    private static PayoutStatus parseStatus(String raw) {
        try {
            return PayoutStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, STATUS_PARAM_MESSAGE);
        }
    }

    private static long requireActualAmount(PayoutOrder payout, Long actualAmount) {
        if (actualAmount == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "按实际金额处置必须提供 actualAmount");
        }
        if (actualAmount < 0 || actualAmount > payout.getAmount()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "actualAmount 必须在 0~" + payout.getAmount() + " 之间");
        }
        return actualAmount;
    }

    private static long requireZeroPayment(String remark) {
        if (remark == null || remark.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "零扣款处置必须填写凭据说明");
        }
        return 0L;
    }
}
