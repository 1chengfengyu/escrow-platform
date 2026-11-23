package com.escrow.platform.settlement.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.settlement.api.ReconDecisionRequest;
import com.escrow.platform.settlement.api.ReconDifferenceView;
import com.escrow.platform.settlement.domain.DifferenceResolution;
import com.escrow.platform.settlement.domain.DifferenceStatus;
import com.escrow.platform.settlement.domain.DifferenceType;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.SettlementDifference;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import com.escrow.platform.settlement.infrastructure.mapper.SettlementDifferenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 对账差异处置四选一（V6，FR-RECON.HANDLE.01）。
 *
 * <p><b>类型与动作强绑定</b>：</p>
 * <ul>
 *   <li>{@code LOST_RESPONSE} → 补记（按通道金额补做打款成功落账）；</li>
 *   <li>{@code CHANNEL_MISSING} → 冲正（分账原路冲回在途，回退待重试）；</li>
 *   <li>{@code AMOUNT_MISMATCH} → 差额调整（本地与通道差额在劳动者与雇主之间搬运）；</li>
 *   <li>{@code RESULT_UNDETERMINED} → 只能忽略（查证未完成，禁止资金处置）。</li>
 * </ul>
 *
 * <p><b>并发与幂等</b>：处置前先条件更新 {@code WHERE id=? AND status='SETTLEMENT_DIFF_PENDING'}，
 * 受影响行数为 0 即已被他人处置；资金动作与状态变更在同一事务内，任一步失败整体回滚，
 * 差异单保持「待处理」，可以再次处置。</p>
 *
 * <p>权限口径与打款处置台一致：只认会话里的当前身份，不接受请求参数覆盖。</p>
 *
 * @since V6
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconHandlingService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String ADMIN_ONLY_MESSAGE = "仅管理员可处置对账差异";
    private static final String PENDING_ONLY_MESSAGE = "差异单不在待处理状态";
    private static final String NOT_FOUND_MESSAGE = "差异单不存在";
    private static final String STATUS_PARAM_MESSAGE =
            "status 只允许 SETTLEMENT_DIFF_PENDING / SETTLEMENT_DIFF_HANDLED / SETTLEMENT_DIFF_IGNORED";

    private final SettlementDifferenceMapper settlementDifferenceMapper;
    private final PayoutOrderMapper payoutOrderMapper;
    private final PayoutTxService payoutTxService;
    private final ReconService reconService;
    private final AuditRecorder auditRecorder;

    /**
     * 分页查询差异单（FR-RECON.HANDLE.01）。
     *
     * @param current 当前登录用户，必须是管理员身份
     * @param status  状态过滤，空表示全部
     * @param date    对账日过滤，空表示全部
     * @param page    页码，从 1 开始
     * @param size    每页条数（1~{@value #MAX_PAGE_SIZE}）
     * @return 分页视图
     */
    public PageView<ReconDifferenceView> list(LoginUser current, String status, LocalDate date,
                                              int page, int size) {
        requireAdmin(current);
        int pageNo = Math.max(1, page);
        int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        LambdaQueryWrapper<SettlementDifference> wrapper =
                new LambdaQueryWrapper<SettlementDifference>()
                        .orderByAsc(SettlementDifference::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(SettlementDifference::getStatus, parseStatus(status));
        }
        if (date != null) {
            wrapper.eq(SettlementDifference::getReconDate, date);
        }
        Page<SettlementDifference> result =
                settlementDifferenceMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return PageView.of(result.getRecords().stream().map(ReconDifferenceView::of).toList(),
                pageNo, pageSize, result.getTotal());
    }

    /**
     * 待处理差异单数量（V7 治理看板用，只读，不做 HTTP 权限校验）。
     *
     * @return {@code status = SETTLEMENT_DIFF_PENDING} 的行数
     */
    public long countPending() {
        Long count = settlementDifferenceMapper.selectCount(
                new LambdaQueryWrapper<SettlementDifference>()
                        .eq(SettlementDifference::getStatus, DifferenceStatus.SETTLEMENT_DIFF_PENDING));
        return count == null ? 0L : count;
    }

    /**
     * 处置一张差异单（FR-RECON.HANDLE.01）。
     *
     * @param current      当前登录用户，必须是管理员身份
     * @param differenceId 差异单 ID
     * @param request      处置动作与说明
     * @return 处置后的差异单
     * @throws ApiException 403 非管理员 / 400 参数或类型不匹配 / 404 差异单不存在 /
     *                      409 不在待处理或资金动作余额不足
     */
    @Transactional
    public ReconDifferenceView handle(LoginUser current, Long differenceId,
                                      ReconDecisionRequest request) {
        requireAdmin(current);
        SettlementDifference difference = settlementDifferenceMapper.selectById(differenceId);
        if (difference == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        if (difference.getStatus() != DifferenceStatus.SETTLEMENT_DIFF_PENDING) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, PENDING_ONLY_MESSAGE);
        }

        DifferenceResolution resolution = request.resolution();
        if (resolution == DifferenceResolution.RECON_IGNORE) {
            if (request.remark() == null || request.remark().isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "忽略差异必须填写说明");
            }
            int rows = settlementDifferenceMapper.update(null,
                    new LambdaUpdateWrapper<SettlementDifference>()
                            .eq(SettlementDifference::getId, differenceId)
                            .eq(SettlementDifference::getStatus, DifferenceStatus.SETTLEMENT_DIFF_PENDING)
                            .set(SettlementDifference::getStatus, DifferenceStatus.SETTLEMENT_DIFF_IGNORED)
                            .set(SettlementDifference::getResolution, DifferenceResolution.RECON_IGNORE)
                            .set(SettlementDifference::getResolverId, current.userId())
                            .set(SettlementDifference::getResolvedAt, LocalDateTime.now()));
            requireSingleRow(rows);
            log.warn("对账差异已忽略 differenceId={} type={} remark={} operator={}",
                    differenceId, difference.getDiffType(), request.remark(), current.userId());
            auditRecorder.record("RECON_DIFF", String.valueOf(differenceId),
                    DifferenceResolution.RECON_IGNORE.name(),
                    DifferenceStatus.SETTLEMENT_DIFF_PENDING.name(),
                    DifferenceStatus.SETTLEMENT_DIFF_IGNORED.name(), request.remark());
            return ReconDifferenceView.of(settlementDifferenceMapper.selectById(differenceId));
        }

        requireTypeMatches(difference.getDiffType(), resolution);
        PayoutOrder payout = payoutOf(difference.getLocalRecordNo());

        // 先抢占差异单，再动资金：资金异常时整体回滚，差异单仍是待处理
        int rows = settlementDifferenceMapper.update(null,
                new LambdaUpdateWrapper<SettlementDifference>()
                        .eq(SettlementDifference::getId, differenceId)
                        .eq(SettlementDifference::getStatus, DifferenceStatus.SETTLEMENT_DIFF_PENDING)
                        .set(SettlementDifference::getStatus, DifferenceStatus.SETTLEMENT_DIFF_HANDLED)
                        .set(SettlementDifference::getResolution, resolution)
                        .set(SettlementDifference::getResolverId, current.userId())
                        .set(SettlementDifference::getResolvedAt, LocalDateTime.now()));
        requireSingleRow(rows);

        switch (resolution) {
            case RECON_BOOK -> payoutTxService.handleReconBook(payout.getId(),
                    requireChannelAmount(difference));
            case RECON_REVERSE -> payoutTxService.handleReconReverse(payout.getId());
            case RECON_ADJUST -> payoutTxService.handleReconAdjust(payout.getId(),
                    requireLocalAmount(difference), requireChannelAmount(difference));
            default -> throw new IllegalStateException("未覆盖的对账处置动作 " + resolution);
        }
        log.warn("对账差异已处置 differenceId={} type={} resolution={} payoutId={} operator={}",
                differenceId, difference.getDiffType(), resolution, payout.getId(), current.userId());
        auditRecorder.record("RECON_DIFF", String.valueOf(differenceId), resolution.name(),
                DifferenceStatus.SETTLEMENT_DIFF_PENDING.name(),
                DifferenceStatus.SETTLEMENT_DIFF_HANDLED.name(),
                "payoutId=" + payout.getId() + " remark=" + request.remark());
        return ReconDifferenceView.of(settlementDifferenceMapper.selectById(differenceId));
    }

    /**
     * 管理端手动触发对账（FR-RECON.JOB.01）。
     *
     * @param current 当前登录用户，必须是管理员身份
     * @param date    对账日期
     * @return 批次结果
     */
    public ReconRun run(LoginUser current, LocalDate date) {
        requireAdmin(current);
        return reconService.runDaily(requireDate(date));
    }

    /**
     * 对账日报（FR-RECON.REPORT.01）。
     *
     * @param current 当前登录用户，必须是管理员身份
     * @param date    对账日期
     * @return 日报
     */
    public ReconReport report(LoginUser current, LocalDate date) {
        requireAdmin(current);
        return reconService.reportOf(requireDate(date));
    }

    private PayoutOrder payoutOf(String requestNo) {
        PayoutOrder payout = payoutOrderMapper.selectOne(new LambdaQueryWrapper<PayoutOrder>()
                .eq(PayoutOrder::getRequestNo, requestNo));
        if (payout == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                    "差异对应的打款单不存在 requestNo=" + requestNo);
        }
        return payout;
    }

    /** 类型与动作的绑定关系（SRS 3.4.6）；RESULT_UNDETERMINED 只能忽略。 */
    private static void requireTypeMatches(DifferenceType type, DifferenceResolution resolution) {
        boolean matched = switch (resolution) {
            case RECON_BOOK -> type == DifferenceType.LOST_RESPONSE;
            case RECON_REVERSE -> type == DifferenceType.CHANNEL_MISSING;
            case RECON_ADJUST -> type == DifferenceType.AMOUNT_MISMATCH;
            case RECON_IGNORE -> false;
        };
        if (!matched) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "差异类型 " + type + " 不允许处置动作 " + resolution);
        }
    }

    private static long requireChannelAmount(SettlementDifference difference) {
        if (difference.getChannelAmount() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "差异单缺少通道金额，无法处置");
        }
        return difference.getChannelAmount();
    }

    private static long requireLocalAmount(SettlementDifference difference) {
        if (difference.getLocalAmount() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "差异单缺少本地金额，无法处置");
        }
        return difference.getLocalAmount();
    }

    private static void requireSingleRow(int rows) {
        if (rows != 1) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, PENDING_ONLY_MESSAGE);
        }
    }

    private static LocalDate requireDate(LocalDate date) {
        if (date == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "date 不能为空");
        }
        return date;
    }

    private static DifferenceStatus parseStatus(String raw) {
        try {
            return DifferenceStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, STATUS_PARAM_MESSAGE);
        }
    }

    private static void requireAdmin(LoginUser current) {
        if (current == null || current.currentIdentity() != IdentityType.ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN, ADMIN_ONLY_MESSAGE);
        }
    }
}
