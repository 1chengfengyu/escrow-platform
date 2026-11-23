package com.escrow.platform.admin.application;

import com.escrow.platform.admin.api.AdminDashboardView;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.dispute.application.DisputeService;
import com.escrow.platform.identity.application.AdminUserService;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.settlement.application.PayoutAdminService;
import com.escrow.platform.settlement.application.ReconHandlingService;
import com.escrow.platform.task.application.TaskQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 治理看板测试（V7，FR-ADMIN.DASH.01）。
 *
 * @since V7
 */
@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);
    private static final LoginUser EMPLOYER =
            new LoginUser(1001L, "employer@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);

    @Mock
    private AdminUserService adminUserService;
    @Mock
    private TaskQueryService taskQueryService;
    @Mock
    private PayoutAdminService payoutAdminService;
    @Mock
    private DisputeService disputeService;
    @Mock
    private ReconHandlingService reconHandlingService;

    @InjectMocks
    private AdminDashboardService service;

    /** 五项计数逐一取自所属域的只读入口。 */
    @Test
    void overviewShouldAggregateDomainCounts() {
        when(adminUserService.countAll()).thenReturn(12L);
        when(taskQueryService.countAll()).thenReturn(34L);
        when(payoutAdminService.countManualReview()).thenReturn(3L);
        when(disputeService.countPending()).thenReturn(2L);
        when(reconHandlingService.countPending()).thenReturn(1L);

        AdminDashboardView view = service.overview(ADMIN);

        assertThat(view.userCount()).isEqualTo(12L);
        assertThat(view.taskCount()).isEqualTo(34L);
        assertThat(view.pendingPayoutCount()).isEqualTo(3L);
        assertThat(view.pendingDisputeCount()).isEqualTo(2L);
        assertThat(view.pendingDifferenceCount()).isEqualTo(1L);
    }

    /** 非管理员：403，且不触发任何跨域查询。 */
    @Test
    void nonAdminShouldBeForbidden() {
        assertThatThrownBy(() -> service.overview(EMPLOYER))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(adminUserService, taskQueryService, payoutAdminService,
                disputeService, reconHandlingService);
    }
}
