package com.escrow.platform.identity.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.SecurityLog;
import com.escrow.platform.identity.api.QualificationSubmitRequest;
import com.escrow.platform.identity.api.QualificationView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.PlatformEmployerQualification;
import com.escrow.platform.identity.domain.ReviewStatus;
import com.escrow.platform.identity.domain.SubjectType;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.identity.infrastructure.mapper.PlatformEmployerQualificationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
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
 * 资质提交 / 查询服务单测（v1-c5 §8 第 2 条）。
 *
 * <p>纯 Mockito：Mapper、安全日志、准入开关全部替身，不连 MySQL / Redis。</p>
 *
 * @since V1
 */
@ExtendWith(MockitoExtension.class)
class QualificationServiceTest {

    private static final String RESOURCE = "identity:qualification";

    private static final LoginUser EMPLOYER =
            new LoginUser(1001L, "13800138000", UserRole.EMPLOYER, IdentityType.EMPLOYER);

    @Mock
    private PlatformEmployerQualificationMapper qualificationMapper;

    @Mock
    private SecurityLog securityLog;

    @Mock
    private QualificationGate qualificationGate;

    @InjectMocks
    private QualificationService qualificationService;

    /**
     * Lambda 条件构造器依赖 {@code TableInfo} 缓存才能把方法引用翻译成列名；
     * 纯单测没有 MyBatis 启动过程，这里手工初始化一次（运行期由 MapperFactoryBean 完成）。
     */
    @BeforeEach
    void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                PlatformEmployerQualification.class);
    }

    /** 首次提交：INSERT，状态 PENDING，提交时间与用户 ID 落库，审核人字段为空。 */
    @Test
    void firstSubmitShouldInsertPendingRecord() {
        when(qualificationMapper.selectOne(any())).thenReturn(null);

        qualificationService.submit(EMPLOYER,
                new QualificationSubmitRequest("INDIVIDUAL", "  张三  ", "1234"));

        ArgumentCaptor<PlatformEmployerQualification> captor =
                ArgumentCaptor.forClass(PlatformEmployerQualification.class);
        verify(qualificationMapper).insert(captor.capture());
        PlatformEmployerQualification inserted = captor.getValue();
        assertThat(inserted.getUserId()).isEqualTo(1001L);
        assertThat(inserted.getSubjectType()).isEqualTo(SubjectType.INDIVIDUAL);
        assertThat(inserted.getSubjectName()).isEqualTo("张三");
        assertThat(inserted.getIdLast4()).isEqualTo("1234");
        assertThat(inserted.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(inserted.getSubmittedAt()).isNotNull();
        assertThat(inserted.getReviewerId()).isNull();
        verify(securityLog).log(SecurityLog.ACTION_QUALIFICATION_SUBMIT, RESOURCE,
                "13800138000", SecurityLog.RESULT_OK);
    }

    /** 二次提交：UPDATE 而不是 INSERT，并显式清空 reviewer_id / review_reason / reviewed_at。 */
    @Test
    void resubmitShouldUpdateAndClearReviewFields() {
        PlatformEmployerQualification existing = existingRecord();
        when(qualificationMapper.selectOne(any())).thenReturn(existing);

        qualificationService.submit(EMPLOYER,
                new QualificationSubmitRequest("SOLE_PROPRIETOR", "小王便利店", "5678"));

        verify(qualificationMapper, never()).insert(any(PlatformEmployerQualification.class));

        ArgumentCaptor<Wrapper<PlatformEmployerQualification>> captor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(qualificationMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<?> wrapper = (LambdaUpdateWrapper<?>) captor.getValue();

        // set 子句必须显式包含要清空的三个审核字段，否则 updateById 的「忽略 null」会让旧理由残留
        assertThat(wrapper.getSqlSet())
                .contains("review_status", "submitted_at", "reviewer_id", "review_reason", "reviewed_at");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(ReviewStatus.PENDING, SubjectType.SOLE_PROPRIETOR, "小王便利店", "5678");
    }

    /** 非雇主身份提交：403 FORBIDDEN + DENIED_NOT_EMPLOYER 安全日志，且不查库。 */
    @Test
    void nonEmployerSubmitShouldBeForbidden() {
        LoginUser worker = new LoginUser(1002L, "13900139000", UserRole.WORKER, IdentityType.WORKER);

        ApiException ex = catchThrowableOfType(() -> qualificationService.submit(worker,
                        new QualificationSubmitRequest("INDIVIDUAL", "张三", "1234")),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(securityLog).log(SecurityLog.ACTION_QUALIFICATION_SUBMIT, RESOURCE,
                "13900139000", SecurityLog.RESULT_DENIED_NOT_EMPLOYER);
        verifyNoInteractions(qualificationMapper);
    }

    /** 字段不合法：400 INVALID_PARAMETER + data.fields，且不查库、不落库。 */
    @Test
    void invalidFieldsShouldReturnFieldsInData() {
        ApiException ex = catchThrowableOfType(() -> qualificationService.submit(EMPLOYER,
                        new QualificationSubmitRequest("INDIVIDUAL", "张三", "12a4")),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> data = (Map<String, Map<String, String>>) ex.getData();
        assertThat(data.get("fields")).containsOnlyKeys("idLast4");
        verifyNoInteractions(qualificationMapper);
    }

    /** 未提交查询：200 + UNSUBMITTED，其余字段为空，publishEligible 由准入开关决定。 */
    @Test
    void getWithoutRecordShouldReturnUnsubmitted() {
        when(qualificationMapper.selectOne(any())).thenReturn(null);
        when(qualificationGate.isPublishEligible(1001L)).thenReturn(false);

        QualificationView view = qualificationService.get(EMPLOYER);

        assertThat(view.status()).isEqualTo(ReviewStatus.UNSUBMITTED.name());
        assertThat(view.subjectType()).isNull();
        assertThat(view.subjectName()).isNull();
        assertThat(view.idLast4()).isNull();
        assertThat(view.reviewReason()).isNull();
        assertThat(view.reviewedAt()).isNull();
        assertThat(view.publishEligible()).isFalse();
    }

    /** 已提交查询：字段原样映射，publishEligible 直接取准入开关的结果。 */
    @Test
    void getWithRecordShouldMapAllFields() {
        PlatformEmployerQualification existing = existingRecord();
        existing.setReviewStatus(ReviewStatus.APPROVED);
        existing.setReviewReason(null);
        when(qualificationMapper.selectOne(any())).thenReturn(existing);
        when(qualificationGate.isPublishEligible(1001L)).thenReturn(true);

        QualificationView view = qualificationService.get(EMPLOYER);

        assertThat(view.status()).isEqualTo("APPROVED");
        assertThat(view.subjectType()).isEqualTo("INDIVIDUAL");
        assertThat(view.subjectName()).isEqualTo("张三");
        assertThat(view.idLast4()).isEqualTo("1234");
        assertThat(view.reviewedAt()).isNotNull();
        assertThat(view.publishEligible()).isTrue();
    }

    private static PlatformEmployerQualification existingRecord() {
        PlatformEmployerQualification q = new PlatformEmployerQualification();
        q.setId(9L);
        q.setUserId(1001L);
        q.setSubjectType(SubjectType.INDIVIDUAL);
        q.setSubjectName("张三");
        q.setIdLast4("1234");
        q.setReviewStatus(ReviewStatus.REJECTED);
        q.setReviewerId(9001L);
        q.setReviewReason("证件不清晰");
        q.setSubmittedAt(LocalDateTime.of(2026, 11, 16, 10, 0));
        q.setReviewedAt(LocalDateTime.of(2026, 11, 16, 11, 0));
        return q;
    }
}
