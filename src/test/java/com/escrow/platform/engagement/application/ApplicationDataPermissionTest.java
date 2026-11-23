package com.escrow.platform.engagement.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.engagement.api.ApplicationListItemView;
import com.escrow.platform.engagement.domain.ApplicationPageQuery;
import com.escrow.platform.engagement.domain.ApplicationStatus;
import com.escrow.platform.engagement.domain.EngagementApplication;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementApplicationMapper;
import com.escrow.platform.identity.application.IdentityProfileService;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.application.TaskParticipationService;
import com.escrow.platform.task.application.TaskParticipationService.ParticipationFacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 报名者列表的数据权限测试（NFR-SEC.02 / 角色矩阵「本任务报名者列表」）。
 *
 * <p>规则：只有该任务的雇主能读；劳动者与其他雇主一律 403。
 * 视图只含公开字段（昵称、报名时间、状态），联系方式与简历不得经本入口泄露。</p>
 *
 * @since V3
 */
@ExtendWith(MockitoExtension.class)
class ApplicationDataPermissionTest {

    private static final long TASK_ID = 10L;
    private static final long OWNER_ID = 1001L;
    private static final LoginUser OWNER =
            new LoginUser(OWNER_ID, "owner@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final LoginUser OTHER_EMPLOYER =
            new LoginUser(1002L, "other@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final LoginUser WORKER =
            new LoginUser(2001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Mock
    private EngagementApplicationMapper applicationMapper;

    @Mock
    private TaskParticipationService taskParticipationService;

    @Mock
    private IdentityProfileService identityProfileService;

    @InjectMocks
    private ApplicationService applicationService;

    /** 劳动者不能看报名者列表：403，且不查任务与报名表。 */
    @Test
    void workerShouldNotListApplicants() {
        ApiException ex = catchThrowableOfType(
                () -> applicationService.list(WORKER, TASK_ID, ApplicationPageQuery.from(null, null)),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(taskParticipationService, applicationMapper);
    }

    /** 其他雇主不能看本任务报名者：403，且不查报名表（防止先查后判）。 */
    @Test
    void otherEmployerShouldNotListApplicants() {
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(publishedTask());

        ApiException ex = catchThrowableOfType(
                () -> applicationService.list(OTHER_EMPLOYER, TASK_ID, ApplicationPageQuery.from(null, null)),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(applicationMapper);
    }

    /** 本任务雇主可读，但视图只含昵称与报名时间；不得调用名片入口拿联系方式。 */
    @Test
    @SuppressWarnings("unchecked")
    void ownerShouldListOnlyPublicFields() {
        when(taskParticipationService.factsOf(TASK_ID)).thenReturn(publishedTask());
        EngagementApplication first = application(5L, 2001L, LocalDateTime.of(2026, 12, 1, 9, 0));
        EngagementApplication second = application(6L, 2002L, LocalDateTime.of(2026, 12, 1, 10, 0));
        Page<EngagementApplication> page = new Page<>(1, 20);
        page.setRecords(List.of(second, first));
        page.setTotal(2);
        when(applicationMapper.selectPage(any(), any())).thenReturn(page);
        when(identityProfileService.nicknames(List.of(2002L, 2001L)))
                .thenReturn(Map.of(2002L, "小李", 2001L, "小王"));

        PageView<ApplicationListItemView> view =
                applicationService.list(OWNER, TASK_ID, ApplicationPageQuery.from(null, null));

        assertThat(view.total()).isEqualTo(2);
        assertThat(view.items()).extracting(ApplicationListItemView::workerNickname)
                .containsExactly("小李", "小王");
        assertThat(view.items()).extracting(ApplicationListItemView::appliedAt)
                .containsExactly(LocalDateTime.of(2026, 12, 1, 10, 0),
                        LocalDateTime.of(2026, 12, 1, 9, 0));
        // 公开入口只有 nicknames；名片（含手机号 / 邮箱）不得为列表服务
        verify(identityProfileService, never()).card(anyLong());
    }

    /** 任务不存在：404，先于归属判定。 */
    @Test
    void missingTaskShouldReturnNotFound() {
        when(taskParticipationService.factsOf(TASK_ID))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在"));

        ApiException ex = catchThrowableOfType(
                () -> applicationService.list(OWNER, TASK_ID, ApplicationPageQuery.from(null, null)),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verifyNoInteractions(applicationMapper);
    }

    private static ParticipationFacts publishedTask() {
        return new ParticipationFacts(TASK_ID, OWNER_ID, LocalDateTime.now().minusDays(1),
                null, LocalDateTime.now().plusDays(1), 1);
    }

    private static EngagementApplication application(Long id, Long workerId, LocalDateTime appliedAt) {
        EngagementApplication application = new EngagementApplication();
        application.setId(id);
        application.setTaskId(TASK_ID);
        application.setWorkerId(workerId);
        application.setStatus(ApplicationStatus.APPLY_PENDING);
        application.setAppliedAt(appliedAt);
        return application;
    }
}
