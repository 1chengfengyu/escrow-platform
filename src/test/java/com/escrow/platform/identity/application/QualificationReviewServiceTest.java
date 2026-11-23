package com.escrow.platform.identity.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.SecurityLog;
import com.escrow.platform.identity.api.QualificationListItem;
import com.escrow.platform.identity.api.QualificationReviewRequest;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.PlatformEmployerQualification;
import com.escrow.platform.identity.domain.PlatformUser;
import com.escrow.platform.identity.domain.ReviewStatus;
import com.escrow.platform.identity.domain.SubjectType;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.identity.infrastructure.mapper.PlatformEmployerQualificationMapper;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 资质审核服务单测（v1-c5 §8 第 3 条，FR-ADMIN.QUAL.01）。
 *
 * @since V1
 */
@ExtendWith(MockitoExtension.class)
class QualificationReviewServiceTest {

    private static final String RESOURCE = "identity:qualification";

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "13900139000", UserRole.ADMIN, IdentityType.ADMIN);

    @Mock
    private PlatformEmployerQualificationMapper qualificationMapper;

    @Mock
    private PlatformUserMapper platformUserMapper;

    @Mock
    private SecurityLog securityLog;

    @Mock
    private com.escrow.platform.common.audit.application.AuditRecorder auditRecorder;
    @InjectMocks
    private QualificationReviewService reviewService;

    /**
     * Lambda 条件构造器依赖 {@code TableInfo} 缓存才能把方法引用翻译成列名；
     * 纯单测没有 MyBatis 启动过程，这里手工初始化一次（运行期由 MapperFactoryBean 完成）。
     */
    @BeforeEach
    void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                PlatformEmployerQualification.class);
    }

    /** 审核通过：条件更新成功，reviewer_id 落当前管理员，结果写 APPROVED 安全日志。 */
    @Test
    void approveShouldPersistReviewerAndLog() {
        when(qualificationMapper.selectOne(any())).thenReturn(pendingRecord());
        when(qualificationMapper.update(isNull(), any())).thenReturn(1);

        reviewService.review(ADMIN, 1001L, new QualificationReviewRequest("APPROVED", "  "));

        ArgumentCaptor<Wrapper<PlatformEmployerQualification>> captor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(qualificationMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<?> wrapper = (LambdaUpdateWrapper<?>) captor.getValue();
        assertThat(wrapper.getSqlSet())
                .contains("review_status", "reviewer_id", "review_reason", "reviewed_at");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(ReviewStatus.APPROVED, 9001L);
        verify(securityLog).log(SecurityLog.ACTION_QUALIFICATION_REVIEW, RESOURCE,
                "13900139000", "APPROVED");
    }

    /** 审核驳回：理由落库，结果写 REJECTED 安全日志。 */
    @Test
    void rejectShouldPersistReasonAndReviewer() {
        when(qualificationMapper.selectOne(any())).thenReturn(pendingRecord());
        when(qualificationMapper.update(isNull(), any())).thenReturn(1);

        reviewService.review(ADMIN, 1001L, new QualificationReviewRequest("REJECTED", "  证件不清晰  "));

        ArgumentCaptor<Wrapper<PlatformEmployerQualification>> captor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(qualificationMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<?> wrapper = (LambdaUpdateWrapper<?>) captor.getValue();
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(ReviewStatus.REJECTED, 9001L, "证件不清晰");
        verify(securityLog).log(SecurityLog.ACTION_QUALIFICATION_REVIEW, RESOURCE,
                "13900139000", "REJECTED");
    }

    /** 驳回但缺理由：400 INVALID_PARAMETER，且在查库前就拒绝。 */
    @Test
    void rejectWithoutReasonShouldFailValidation() {
        ApiException ex = catchThrowableOfType(() -> reviewService.review(ADMIN, 1001L,
                        new QualificationReviewRequest("REJECTED", "   ")),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> data = (Map<String, Map<String, String>>) ex.getData();
        assertThat(data.get("fields")).containsOnlyKeys("reason");
        verifyNoInteractions(qualificationMapper);
    }

    /** decision 不是 APPROVED / REJECTED：400。 */
    @Test
    void invalidDecisionShouldFailValidation() {
        ApiException ex = catchThrowableOfType(() -> reviewService.review(ADMIN, 1001L,
                        new QualificationReviewRequest("PENDING", null)),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(qualificationMapper);
    }

    /** 目标用户没有资质记录：404 RESOURCE_NOT_FOUND。 */
    @Test
    void missingRecordShouldReturnNotFound() {
        when(qualificationMapper.selectOne(any())).thenReturn(null);

        ApiException ex = catchThrowableOfType(() -> reviewService.review(ADMIN, 1001L,
                        new QualificationReviewRequest("APPROVED", null)),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(qualificationMapper, never()).update(any(), any());
    }

    /** 条件更新影响 0 行（不在待审核 / 已被并发审掉）：409 CONFLICT，且不写安全日志。 */
    @Test
    void nonPendingRecordShouldConflict() {
        when(qualificationMapper.selectOne(any())).thenReturn(pendingRecord());
        when(qualificationMapper.update(isNull(), any())).thenReturn(0);

        ApiException ex = catchThrowableOfType(() -> reviewService.review(ADMIN, 1001L,
                        new QualificationReviewRequest("APPROVED", null)),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(ex.getMessage()).isEqualTo("该资质不处于待审核状态");
        verify(securityLog, never()).log(any(), any(), any(), any());
    }

    /** 非管理员审核：403 FORBIDDEN，且不查库。 */
    @Test
    void nonAdminReviewShouldBeForbidden() {
        LoginUser worker = new LoginUser(1002L, "13800138000", UserRole.WORKER, IdentityType.WORKER);

        ApiException ex = catchThrowableOfType(() -> reviewService.review(worker, 1001L,
                        new QualificationReviewRequest("APPROVED", null)),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(qualificationMapper);
    }

    /** 列表缺省状态：按 PENDING 查，返回昵称与提交时间，按行序映射。 */
    @Test
    void listShouldDefaultToPendingAndJoinNickname() {
        PlatformEmployerQualification first = pendingRecord();
        PlatformEmployerQualification second = pendingRecord();
        second.setUserId(1002L);
        second.setSubjectName("小王便利店");
        second.setSubjectType(SubjectType.SOLE_PROPRIETOR);
        second.setSubmittedAt(LocalDateTime.of(2026, 11, 16, 9, 30));
        when(qualificationMapper.selectList(any())).thenReturn(List.of(first, second));
        when(platformUserMapper.selectList(any())).thenReturn(List.of(user(1001L, "张三"), user(1002L, "小王")));

        List<QualificationListItem> items = reviewService.list(ADMIN, null);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).userId()).isEqualTo(1001L);
        assertThat(items.get(0).nickname()).isEqualTo("张三");
        assertThat(items.get(0).subjectType()).isEqualTo("INDIVIDUAL");
        assertThat(items.get(1).nickname()).isEqualTo("小王");
        assertThat(items.get(1).subjectType()).isEqualTo("SOLE_PROPRIETOR");
    }

    /** 列表状态不在白名单（含 UNSUBMITTED）：400，且不查库。 */
    @Test
    void listWithInvalidStatusShouldFail() {
        ApiException ex = catchThrowableOfType(() -> reviewService.list(ADMIN, "UNSUBMITTED"),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(qualificationMapper);
    }

    /** 非管理员查看列表：403，且不查库。 */
    @Test
    void nonAdminListShouldBeForbidden() {
        LoginUser worker = new LoginUser(1002L, "13800138000", UserRole.WORKER, IdentityType.WORKER);

        ApiException ex = catchThrowableOfType(() -> reviewService.list(worker, null),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(qualificationMapper);
    }

    private static PlatformEmployerQualification pendingRecord() {
        PlatformEmployerQualification q = new PlatformEmployerQualification();
        q.setId(9L);
        q.setUserId(1001L);
        q.setSubjectType(SubjectType.INDIVIDUAL);
        q.setSubjectName("张三");
        q.setIdLast4("1234");
        q.setReviewStatus(ReviewStatus.PENDING);
        q.setSubmittedAt(LocalDateTime.of(2026, 11, 16, 10, 0));
        return q;
    }

    private static PlatformUser user(Long id, String nickname) {
        PlatformUser user = new PlatformUser();
        user.setId(id);
        user.setNickname(nickname);
        return user;
    }
}
