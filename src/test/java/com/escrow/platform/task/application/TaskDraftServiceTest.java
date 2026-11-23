package com.escrow.platform.task.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.api.TaskDraftRequest;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.infrastructure.cache.TaskDetailCache;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 任务草稿服务单测（v2-c2 卡片 §8 第 5 行，11 个用例，Mockito 无 MySQL）。
 *
 * <p>覆盖：身份门禁、字段校验拦截、任务不存在 / 非本人、价格锁（改单价 / 改名额数）、
 * 草稿价格放行、已发布编辑不动派生列、过去时间放行、settlement_note 显式清空。</p>
 *
 * @since V2
 */
@ExtendWith(MockitoExtension.class)
class TaskDraftServiceTest {

    private static final long TASK_ID = 1234567890123456789L;

    private static final LoginUser EMPLOYER =
            new LoginUser(1001L, "13800138000", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final LoginUser WORKER =
            new LoginUser(1002L, "13900139000", UserRole.WORKER, IdentityType.WORKER);

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private TaskDetailCache taskDetailCache;

    private TaskDraftService service;

    /**
     * Lambda 条件构造器依赖 {@code TableInfo} 缓存才能把方法引用翻译成列名；
     * 纯单测没有 MyBatis 启动过程，这里手工初始化一次（运行期由 MapperFactoryBean 完成）。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Task.class);
    }

    @BeforeEach
    void setUp() {
        service = new TaskDraftService(taskMapper, taskDetailCache);
    }

    /** 全部合法的基线请求。 */
    private static TaskDraftRequest valid() {
        return new TaskDraftRequest("周末小区派单", "帮忙派发传单", "地推", 5000L, 3,
                LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2),
                "北京市朝阳区", "验收后结算");
    }

    /** 已发布任务（招募中）：published_at 非空。 */
    private static Task publishedTask() {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setEmployerId(1001L);
        task.setUnitPrice(5000L);
        task.setSlotCount(3);
        task.setPublishedAt(LocalDateTime.now().minusDays(1));
        return task;
    }

    /** 草稿任务：published_at 为空。 */
    private static Task draftTask() {
        Task task = publishedTask();
        task.setPublishedAt(null);
        return task;
    }

    /** 创建草稿：插入时带上派生事实初值，雇主归属正确，返回 ID。 */
    @Test
    void createShouldInsertDraftWithDerivedFacts() {
        doAnswer(inv -> {
            ((Task) inv.getArgument(0)).setId(TASK_ID);
            return 1;
        }).when(taskMapper).insert(any(Task.class));

        Long id = service.create(EMPLOYER, valid());

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskMapper).insert(captor.capture());
        Task saved = captor.getValue();
        assertThat(id).isEqualTo(TASK_ID).isEqualTo(saved.getId());
        assertThat(saved.getEmployerId()).isEqualTo(1001L);
        assertThat(saved.getTitle()).isEqualTo("周末小区派单");
        assertThat(saved.getUnitPrice()).isEqualTo(5000L);
        assertThat(saved.getSlotCount()).isEqualTo(3);
        // 派生口径初值：草稿事实，名额数还没有快照费率
        assertThat(saved.getPublishedAt()).isNull();
        assertThat(saved.getCancelledAt()).isNull();
        assertThat(saved.getRecruitingClosed()).isFalse();
        assertThat(saved.getFeeRate()).isNull();
    }

    /** 非雇主身份创建：403 FORBIDDEN，且不落库（FR-TASK.CREATE.03）。 */
    @Test
    void createShouldRejectNonEmployer() {
        assertThatThrownBy(() -> service.create(WORKER, valid()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(ex.getErrorCode().getHttpStatus()).isEqualTo(403);
                });
        verifyNoInteractions(taskMapper);
    }

    /** 字段不合法：400 INVALID_PARAMETER + data.fields 指明字段，且不落库。 */
    @Test
    void createShouldRejectInvalidFieldsWithFieldMap() {
        TaskDraftRequest bad = new TaskDraftRequest(null, "描述", "地推", 0L, 3,
                LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2),
                "北京市朝阳区", null);

        assertThatThrownBy(() -> service.create(EMPLOYER, bad))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
                    assertThat(ex.getErrorCode().getHttpStatus()).isEqualTo(400);
                    @SuppressWarnings("unchecked")
                    Map<String, String> fields = ((Map<String, Map<String, String>>) ex.getData()).get("fields");
                    assertThat(fields).containsKeys("title", "unitPrice");
                });
        verifyNoInteractions(taskMapper);
    }

    /** 任务不存在：404 RESOURCE_NOT_FOUND。 */
    @Test
    void updateShouldRejectMissingTask() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.update(EMPLOYER, TASK_ID, valid()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 非本人任务：403 FORBIDDEN（FR-TASK.CREATE.04 越权边界）。 */
    @Test
    void updateShouldRejectForeignTask() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask());
        LoginUser otherEmployer =
                new LoginUser(9999L, "13700137000", UserRole.EMPLOYER, IdentityType.EMPLOYER);

        assertThatThrownBy(() -> service.update(otherEmployer, TASK_ID, valid()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    /** 已发布改单价：409 TASK_PRICE_LOCKED（FR-TASK.CREATE.05）。 */
    @Test
    void updatePublishedPriceChangeShouldBePriceLocked() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask());
        TaskDraftRequest req = new TaskDraftRequest("周末小区派单", "帮忙派发传单", "地推",
                6000L, 3, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2),
                "北京市朝阳区", null);

        assertThatThrownBy(() -> service.update(EMPLOYER, TASK_ID, req))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TASK_PRICE_LOCKED);
                    assertThat(ex.getErrorCode().getHttpStatus()).isEqualTo(409);
                });
        verify(taskMapper, never()).update(any(Task.class), any());
    }

    /** 已发布改名额数：同样 409 TASK_PRICE_LOCKED。 */
    @Test
    void updatePublishedSlotCountChangeShouldBePriceLocked() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask());
        TaskDraftRequest req = new TaskDraftRequest("周末小区派单", "帮忙派发传单", "地推",
                5000L, 4, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2),
                "北京市朝阳区", null);

        assertThatThrownBy(() -> service.update(EMPLOYER, TASK_ID, req))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TASK_PRICE_LOCKED));
        verify(taskMapper, never()).update(any(Task.class), any());
    }

    /** 草稿改单价：放行（价格锁只约束发布之后）。 */
    @Test
    void updateDraftPriceChangeShouldSucceed() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(draftTask());
        TaskDraftRequest req = new TaskDraftRequest("周末小区派单", "帮忙派发传单", "地推",
                6000L, 3, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2),
                "北京市朝阳区", null);

        service.update(EMPLOYER, TASK_ID, req);
        verify(taskMapper).update(isNull(), any());
    }

    /** 已发布编辑其他字段：更新语句只碰 9 个可编辑字段，不动派生列与归属列。 */
    @Test
    @SuppressWarnings("unchecked")
    void updatePublishedOtherFieldsShouldNotTouchDerivedColumns() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask());

        service.update(EMPLOYER, TASK_ID, valid());

        ArgumentCaptor<Wrapper<Task>> captor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(taskMapper).update(isNull(), captor.capture());
        String sqlSet = ((LambdaUpdateWrapper<Task>) captor.getValue()).getSqlSet();
        assertThat(sqlSet).contains("description");
        assertThat(sqlSet)
                .doesNotContain("published_at")
                .doesNotContain("cancelled_at")
                .doesNotContain("recruiting_closed")
                .doesNotContain("fee_rate")
                .doesNotContain("employer_id");
    }

    /** 已发布任务编辑允许过去时间（FR-TASK.CREATE.05，卡片 §1.3 第 9 条）。 */
    @Test
    void updatePublishedShouldAllowPastStartTime() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask());
        TaskDraftRequest req = new TaskDraftRequest("周末小区派单", "帮忙派发传单", "地推",
                5000L, 3, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1),
                "北京市朝阳区", null);

        service.update(EMPLOYER, TASK_ID, req);
        verify(taskMapper).update(isNull(), any());
    }

    /** settlementNote 传 null：LambdaUpdateWrapper 必须显式 set null，让数据库把旧值清掉。 */
    @Test
    @SuppressWarnings("unchecked")
    void updateShouldExplicitlySetNullSettlementNote() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(draftTask());
        TaskDraftRequest req = new TaskDraftRequest("周末小区派单", "帮忙派发传单", "地推",
                5000L, 3, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2),
                "北京市朝阳区", null);

        service.update(EMPLOYER, TASK_ID, req);

        ArgumentCaptor<Wrapper<Task>> captor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(taskMapper).update(isNull(), captor.capture());
        String sqlSet = ((LambdaUpdateWrapper<Task>) captor.getValue()).getSqlSet();
        assertThat(sqlSet).contains("settlement_note");
    }

    /** 已撤销任务不可编辑：409 终态拒绝，且不产生更新语句。 */
    @Test
    void updateCancelledTaskShouldBeRejected() {
        Task cancelled = draftTask();
        cancelled.setCancelledAt(LocalDateTime.now().minusMinutes(1));
        when(taskMapper.selectById(TASK_ID)).thenReturn(cancelled);

        assertThatThrownBy(() -> service.update(EMPLOYER, TASK_ID, valid()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));

        verify(taskMapper, never()).update(any(Task.class), any());
    }

    /** 编辑成功后必须驱逐详情缓存（Cache-Aside，无事务时立即执行）。 */
    @Test
    void updateShouldEvictDetailCache() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(draftTask());

        service.update(EMPLOYER, TASK_ID, valid());

        verify(taskDetailCache).evict(TASK_ID);
    }
}
