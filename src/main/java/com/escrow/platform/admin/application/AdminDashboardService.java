package com.escrow.platform.admin.application;

import com.escrow.platform.admin.api.AdminDashboardView;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.dispute.application.DisputeService;
import com.escrow.platform.identity.application.AdminUserService;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.settlement.application.PayoutAdminService;
import com.escrow.platform.settlement.application.ReconHandlingService;
import com.escrow.platform.task.application.TaskQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 治理看板（V7，SRS 未单列，见 docs/decisions.md #51）。
 *
 * <p>聚合五个只读计数，每个都走所属域的 Service 入口，不直接查对方的 Mapper、
 * 也不跨域 join——这条线就是 V8 拆服务时的调用边界。</p>
 *
 * @since V7
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private static final String ADMIN_ONLY_MESSAGE = "仅管理员可查看看板";

    private final AdminUserService adminUserService;
    private final TaskQueryService taskQueryService;
    private final PayoutAdminService payoutAdminService;
    private final DisputeService disputeService;
    private final ReconHandlingService reconHandlingService;

    /**
     * 汇总看板计数。
     *
     * @param current 会话主体，必须是 ADMIN
     * @return 五项计数
     * @throws ApiException 403 非管理员
     */
    @Transactional(readOnly = true)
    public AdminDashboardView overview(LoginUser current) {
        requireAdmin(current);
        return new AdminDashboardView(
                adminUserService.countAll(),
                taskQueryService.countAll(),
                payoutAdminService.countManualReview(),
                disputeService.countPending(),
                reconHandlingService.countPending());
    }

    private static void requireAdmin(LoginUser current) {
        if (current == null || current.currentIdentity() != IdentityType.ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN, ADMIN_ONLY_MESSAGE);
        }
    }
}
