package com.escrow.platform.task.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.fund.application.EscrowService;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.api.TaskDetailView;
import com.escrow.platform.task.api.TaskDraftRequest;
import com.escrow.platform.task.api.TaskListItemView;
import com.escrow.platform.task.domain.OpenDisputeQuery;
import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.domain.TaskDisplayStatus;
import com.escrow.platform.task.domain.TaskSearchCriteria;
import com.escrow.platform.task.domain.TaskSlot;
import com.escrow.platform.task.infrastructure.cache.InMemoryTaskDetailCacheStore;
import com.escrow.platform.task.infrastructure.cache.TaskDetailCache;
import com.escrow.platform.task.infrastructure.cache.TaskDetailCacheStore;
import com.escrow.platform.task.infrastructure.cache.TaskDetailSnapshot;
import com.escrow.platform.task.infrastructure.mapper.TaskListItemRow;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
import com.escrow.platform.task.infrastructure.mapper.TaskSlotMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TaskQueryService} 的 Mockito 单测（v2-c3 卡片 §6.1 / §8 第 3 行）。
 *
 * <p>只 mock 两个 Mapper 与 fund 域入口，不连 MySQL；列表验「传参 / 转换 / 空值防御 / total 透传」，
 * 详情验「404 / 草稿可见性 / 名额明细 / 两个标记 / escrowAmount 当事人可见且仅 miss 时查 fund」。</p>
 *
 * @since V2
 */
@ExtendWith(MockitoExtension.class)
class TaskQueryServiceTest {

    private static final LoginUser EMPLOYER =
            new LoginUser(1001L, "13800138000", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final LoginUser OTHER =
            new LoginUser(2002L, "13900139000", UserRole.WORKER, IdentityType.WORKER);
    private static final Long TASK_ID = 2000001L;

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private TaskSlotMapper taskSlotMapper;

    @Mock
    private EscrowService escrowService;

    @Mock
    private OpenDisputeQuery openDisputeQuery;

    private TaskDetailCache taskDetailCache;
    private TaskQueryService service;

    /**
     * 草稿编辑用到 {@code LambdaUpdateWrapper}，需先初始化 {@code TableInfo} 缓存；
     * 纯单测没有 MyBatis 启动过程，这里手工初始化一次（与 {@code TaskDraftServiceTest} 一致）。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Task.class);
        TableInfoHelper.initTableInfo(assistant, TaskSlot.class);
    }

    @BeforeEach
    void setUp() {
        taskDetailCache = new TaskDetailCache(
                new InMemoryTaskDetailCacheStore(), new InMemoryTaskDetailCacheStore(), taskId -> { });
        service = new TaskQueryService(taskMapper, taskSlotMapper, taskDetailCache, escrowService,
                openDisputeQuery);
    }

    /** 列表把页码与条数原样交给分页对象。 */
    @Test
    void listShouldPassPageAndSizeToMapper() {
        when(taskMapper.selectTaskPage(any(), any())).thenReturn(pageOf(List.of(), 0));
        TaskSearchCriteria criteria = TaskSearchCriteria.from(
                null, null, null, null, null, null, null, "2", "5", null, null);

        service.list(criteria);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Page<TaskListItemRow>> captor = ArgumentCaptor.forClass(Page.class);
        verify(taskMapper).selectTaskPage(captor.capture(), any());
        assertThat(captor.getValue().getCurrent()).isEqualTo(2);
        assertThat(captor.getValue().getSize()).isEqualTo(5);
    }

    /** 行对象逐字段转换为列表项（含两个布尔标记）。 */
    @Test
    void listShouldMapRowToView() {
        TaskListItemRow row = row();
        when(taskMapper.selectTaskPage(any(), any())).thenReturn(pageOf(List.of(row), 1));

        PageView<TaskListItemView> view = service.list(defaultCriteria());

        assertThat(view.items()).hasSize(1);
        TaskListItemView item = view.items().get(0);
        assertThat(item.id()).isEqualTo(TASK_ID);
        assertThat(item.title()).isEqualTo("周末小区派单");
        assertThat(item.category()).isEqualTo("地推");
        assertThat(item.unitPrice()).isEqualTo(5000L);
        assertThat(item.workLocation()).isEqualTo("北京");
        assertThat(item.startTime()).isEqualTo(LocalDateTime.of(2026, 12, 1, 9, 0));
        assertThat(item.endTime()).isEqualTo(LocalDateTime.of(2026, 12, 2, 18, 0));
        assertThat(item.publishedAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 10, 0));
        assertThat(item.remainingSlots()).isEqualTo(2);
        assertThat(item.hasOpenDispute()).isFalse();
        assertThat(item.hasSettlementAbnormal()).isTrue();
    }

    /** Boolean 为 null 时按 false 处理，避免列表项出现 null 布尔。 */
    @Test
    void listShouldTreatNullBooleansAsFalse() {
        TaskListItemRow row = row();
        row.setHasOpenDispute(null);
        row.setHasSettlementAbnormal(null);
        when(taskMapper.selectTaskPage(any(), any())).thenReturn(pageOf(List.of(row), 1));

        TaskListItemView item = service.list(defaultCriteria()).items().get(0);

        assertThat(item.hasOpenDispute()).isFalse();
        assertThat(item.hasSettlementAbnormal()).isFalse();
    }

    /** 空结果：items 空、total=0、totalPages=0，不抛异常。 */
    @Test
    void listShouldReturnEmptyItemsWhenNoData() {
        when(taskMapper.selectTaskPage(any(), any())).thenReturn(pageOf(List.of(), 0));

        PageView<TaskListItemView> view = service.list(defaultCriteria());

        assertThat(view.items()).isEmpty();
        assertThat(view.total()).isZero();
        assertThat(view.totalPages()).isZero();
    }

    /** total 透传并按 size 计算总页数（41 条 / 20 条每页 = 3 页）。 */
    @Test
    void listShouldPropagateTotalAndComputeTotalPages() {
        when(taskMapper.selectTaskPage(any(), any())).thenReturn(pageOf(List.of(), 41));

        PageView<TaskListItemView> view = service.list(defaultCriteria());

        assertThat(view.total()).isEqualTo(41L);
        assertThat(view.totalPages()).isEqualTo(3);
    }

    /** 详情：任务不存在 → 404，且不查名额表。 */
    @Test
    void detailShouldThrow404WhenTaskMissing() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class, () -> service.detail(OTHER, TASK_ID));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(ex.getMessage()).isEqualTo("任务不存在");
        verifyNoInteractions(taskSlotMapper);
    }

    /** 详情：草稿对非雇主本人 → 404（不暴露存在性）。 */
    @Test
    void detailShouldThrow404ForDraftViewedByNonOwner() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask(null));

        ApiException ex = assertThrows(ApiException.class, () -> service.detail(OTHER, TASK_ID));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 详情：草稿对雇主本人 200，slotOccupancy 为空数组、remainingSlots=0、publishedAt=null。 */
    @Test
    void detailShouldAllowOwnerToReadDraftWithEmptySlotList() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(draftTask());
        when(taskSlotMapper.selectList(any())).thenReturn(List.of());

        TaskDetailView view = service.detail(EMPLOYER, TASK_ID);

        assertThat(view.publishedAt()).isNull();
        assertThat(view.slotOccupancy()).isEmpty();
        assertThat(view.remainingSlots()).isZero();
        assertThat(view.partyView()).isTrue();
    }

    /** 详情：已发布任务对非当事人可读，但不返回名额明细（null 键不出现）。 */
    @Test
    void detailShouldHideSlotOccupancyFromNonParty() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));
        when(taskSlotMapper.selectList(any()))
                .thenReturn(List.of(slot(1, SlotStatus.SLOT_AVAILABLE), slot(2, SlotStatus.SLOT_OCCUPIED)));

        TaskDetailView view = service.detail(OTHER, TASK_ID);

        assertThat(view.partyView()).isFalse();
        assertThat(view.slotOccupancy()).isNull();
        assertThat(view.remainingSlots()).isEqualTo(1);
    }

    /** 详情：当事人视图带名额明细，顺序与 Mapper 返回的 seqNo 升序一致。 */
    @Test
    void detailShouldReturnOrderedSlotOccupancyForParty() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));
        when(taskSlotMapper.selectList(any()))
                .thenReturn(List.of(slot(1, SlotStatus.SLOT_AVAILABLE), slot(2, SlotStatus.SLOT_PENDING_ARBITRATION)));

        TaskDetailView view = service.detail(EMPLOYER, TASK_ID);

        assertThat(view.slotOccupancy()).extracting(TaskDetailView.SlotItem::seqNo).containsExactly(1, 2);
        assertThat(view.slotOccupancy()).extracting(TaskDetailView.SlotItem::status)
                .containsExactly(SlotStatus.SLOT_AVAILABLE, SlotStatus.SLOT_PENDING_ARBITRATION);
    }

    /** 存在待裁决名额：hasSettlementAbnormal=true。 */
    @Test
    void detailShouldFlagSettlementAbnormalWhenPendingArbitrationExists() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));
        when(taskSlotMapper.selectList(any()))
                .thenReturn(List.of(slot(1, SlotStatus.SLOT_AVAILABLE),
                        slot(2, SlotStatus.SLOT_PENDING_ARBITRATION)));

        assertThat(service.detail(OTHER, TASK_ID).hasSettlementAbnormal()).isTrue();
    }

    /** 存在待裁决名额且争议未决：派生状态为 R2 争议中（V7 修复 openDisputeCount 恒 0）。 */
    @Test
    void detailShouldReturnInDisputeWhenOpenDisputeExists() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));
        when(taskSlotMapper.selectList(any()))
                .thenReturn(List.of(slot(1, SlotStatus.SLOT_DELIVERED),
                        slot(2, SlotStatus.SLOT_PENDING_ARBITRATION)));
        when(openDisputeQuery.countOpen(any())).thenReturn(1);

        TaskDetailView view = service.detail(OTHER, TASK_ID);

        assertThat(view.status()).isEqualTo(TaskDisplayStatus.TASK_IN_DISPUTE);
        assertThat(view.hasSettlementAbnormal()).isTrue();
    }

    /** 全部名额都不是待裁决：hasSettlementAbnormal=false。 */
    @Test
    void detailShouldNotFlagSettlementAbnormalWithoutPendingArbitration() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));
        when(taskSlotMapper.selectList(any()))
                .thenReturn(List.of(slot(1, SlotStatus.SLOT_AVAILABLE), slot(2, SlotStatus.SLOT_OCCUPIED)));

        assertThat(service.detail(OTHER, TASK_ID).hasSettlementAbnormal()).isFalse();
    }

    /** 剩余名额 = 待占用名额数（3 个名额里 2 个待占用）。 */
    @Test
    void detailShouldCountRemainingSlotsAsAvailableSlots() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));
        when(taskSlotMapper.selectList(any()))
                .thenReturn(List.of(slot(1, SlotStatus.SLOT_AVAILABLE),
                        slot(2, SlotStatus.SLOT_AVAILABLE),
                        slot(3, SlotStatus.SLOT_OCCUPIED)));

        assertThat(service.detail(OTHER, TASK_ID).remainingSlots()).isEqualTo(2);
    }

    /** 托管总额只对当事人投影；同一任务非当事人仍为 null（FR-TASK.DETAIL.01 / .02）。 */
    @Test
    void detailShouldProjectEscrowAmountForPartyOnly() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));
        when(taskSlotMapper.selectList(any())).thenReturn(List.of());
        when(escrowService.totalAmountOf(TASK_ID)).thenReturn(10_000L);

        assertThat(service.detail(EMPLOYER, TASK_ID).escrowAmount()).isEqualTo(10_000L);
        assertThat(service.detail(OTHER, TASK_ID).escrowAmount()).isNull();
    }

    /** 无托管单返回 null，不是 0（0 会被前端渲染成「已托管 0 元」）。 */
    @Test
    void detailShouldKeepEscrowAmountNullWhenNotEscrowed() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));
        when(taskSlotMapper.selectList(any())).thenReturn(List.of());
        // Mockito 对 Long 返回类型的默认值是 0，必须显式返回 null 才能验证「不是 0」的契约
        when(escrowService.totalAmountOf(TASK_ID)).thenReturn(null);

        assertThat(service.detail(EMPLOYER, TASK_ID).escrowAmount()).isNull();
    }

    /** 只在缓存 miss 时查 fund 域；第二次详情走缓存，不再查（v2-c6 §5 第 2 条）。 */
    @Test
    void detailShouldQueryFundOnlyOnCacheMiss() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));
        when(taskSlotMapper.selectList(any())).thenReturn(List.of());
        when(escrowService.totalAmountOf(TASK_ID)).thenReturn(10_000L);

        service.detail(EMPLOYER, TASK_ID);
        service.detail(OTHER, TASK_ID);

        verify(escrowService, times(1)).totalAmountOf(TASK_ID);
    }

    /** hasOpenDispute 在 V2 恒 false（禁止跨域 join dispute 表）。 */
    @Test
    void detailShouldKeepOpenDisputeFalse() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));
        when(taskSlotMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.detail(OTHER, TASK_ID).hasOpenDispute()).isFalse();
    }

    /** 草稿：派生状态为 TASK_DRAFT，且招募未收口（R0 压过兜底 R12）。 */
    @Test
    void detailShouldDeriveDraftStatus() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(draftTask());
        when(taskSlotMapper.selectList(any())).thenReturn(List.of());

        TaskDetailView view = service.detail(EMPLOYER, TASK_ID);

        assertThat(view.status()).isEqualTo(TaskDisplayStatus.TASK_DRAFT);
        assertThat(view.recruitingClosed()).isFalse();
    }

    /** 有待占用名额且未到期：TASK_RECRUITING（FR-TASK.DETAIL.02 R1）。 */
    @Test
    void detailShouldDeriveRecruitingStatus() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));
        when(taskSlotMapper.selectList(any()))
                .thenReturn(List.of(slot(1, SlotStatus.SLOT_AVAILABLE), slot(2, SlotStatus.SLOT_OCCUPIED)));

        TaskDetailView view = service.detail(OTHER, TASK_ID);

        assertThat(view.status()).isEqualTo(TaskDisplayStatus.TASK_RECRUITING);
        assertThat(view.recruitingClosed()).isFalse();
    }

    /** 劳动者已交付：TASK_PENDING_ACCEPTANCE（R8，v3-c4 交付后详情必须变化）。 */
    @Test
    void detailShouldDerivePendingAcceptanceAfterDelivery() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));
        when(taskSlotMapper.selectList(any()))
                .thenReturn(List.of(slot(1, SlotStatus.SLOT_DELIVERED)));

        assertThat(service.detail(OTHER, TASK_ID).status())
                .isEqualTo(TaskDisplayStatus.TASK_PENDING_ACCEPTANCE);
    }

    /** 已过结束时间：recruitingClosed=true，在途名额任务显示 TASK_RECRUITING_CLOSED（R11）。 */
    @Test
    void detailShouldCloseRecruitingWhenEndTimePassed() {
        Task expired = publishedTask(LocalDateTime.now().minusDays(2));
        expired.setEndTime(LocalDateTime.now().minusMinutes(1));
        when(taskMapper.selectById(TASK_ID)).thenReturn(expired);
        when(taskSlotMapper.selectList(any()))
                .thenReturn(List.of(slot(1, SlotStatus.SLOT_AVAILABLE)));

        TaskDetailView view = service.detail(OTHER, TASK_ID);

        assertThat(view.recruitingClosed()).isTrue();
        assertThat(view.status()).isEqualTo(TaskDisplayStatus.TASK_RECRUITING_CLOSED);
    }

    /** 已撤单且名额全部终态：TASK_CANCELLED（R4），recruitingClosed=true。 */
    @Test
    void detailShouldDeriveCancelledStatus() {
        Task cancelled = publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0));
        cancelled.setCancelledAt(LocalDateTime.of(2026, 11, 21, 12, 0));
        when(taskMapper.selectById(TASK_ID)).thenReturn(cancelled);
        when(taskSlotMapper.selectList(any()))
                .thenReturn(List.of(slot(1, SlotStatus.SLOT_RELEASED), slot(2, SlotStatus.SLOT_RELEASED)));

        TaskDetailView view = service.detail(OTHER, TASK_ID);

        assertThat(view.status()).isEqualTo(TaskDisplayStatus.TASK_CANCELLED);
        assertThat(view.recruitingClosed()).isTrue();
    }

    /** 已发布任务对未登录用户可读（viewer=null），partyView=false。 */
    @Test
    void detailShouldBeReadableByAnonymousWhenPublished() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));
        when(taskSlotMapper.selectList(any())).thenReturn(List.of());

        TaskDetailView view = service.detail(null, TASK_ID);

        assertThat(view.partyView()).isFalse();
        assertThat(view.slotOccupancy()).isNull();
    }

    /** 已撤单但仍已发布：对非当事人可读（历史保留）。 */
    @Test
    void detailShouldRemainReadableWhenCancelledButPublished() {
        Task task = publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0));
        task.setCancelledAt(LocalDateTime.of(2026, 11, 21, 12, 0));
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(taskSlotMapper.selectList(any())).thenReturn(List.of());

        TaskDetailView view = service.detail(OTHER, TASK_ID);

        assertThat(view.cancelledAt()).isEqualTo(LocalDateTime.of(2026, 11, 21, 12, 0));
        assertThat(view.partyView()).isFalse();
    }

    /** 详情按主键查任务：确认没有走列表 SQL。 */
    @Test
    void detailShouldSelectTaskById() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));
        when(taskSlotMapper.selectList(any())).thenReturn(List.of());

        service.detail(OTHER, TASK_ID);

        verify(taskMapper).selectById(TASK_ID);
        verify(taskMapper, org.mockito.Mockito.never()).selectTaskPage(any(), any());
    }

    /** 同一 id 先当事人后非当事人：两次都 200，名额明细不串，且 DB 只查一次。 */
    @Test
    void detailShouldNotLeakSlotOccupancyAcrossViewers() {
        when(taskMapper.selectById(TASK_ID))
                .thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));
        when(taskSlotMapper.selectList(any()))
                .thenReturn(List.of(slot(1, SlotStatus.SLOT_AVAILABLE),
                        slot(2, SlotStatus.SLOT_OCCUPIED)));

        TaskDetailView partyView = service.detail(EMPLOYER, TASK_ID);
        TaskDetailView nonPartyView = service.detail(OTHER, TASK_ID);

        assertThat(partyView.partyView()).isTrue();
        assertThat(partyView.slotOccupancy()).isNotNull().hasSize(2);
        assertThat(nonPartyView.partyView()).isFalse();
        assertThat(nonPartyView.slotOccupancy()).isNull();
        verify(taskMapper).selectById(TASK_ID);
    }

    /** 写后读：update 提交后驱逐缓存，紧接着 detail 读到新值。 */
    @Test
    void detailShouldReadNewValueAfterUpdate() {
        Task oldTask = publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0));
        oldTask.setTitle("旧标题");
        when(taskMapper.selectById(TASK_ID)).thenReturn(oldTask);
        when(taskSlotMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.detail(EMPLOYER, TASK_ID).title()).isEqualTo("旧标题");

        TaskDraftService draftService = new TaskDraftService(taskMapper, taskDetailCache);
        TaskDraftRequest req = new TaskDraftRequest("新标题", "帮忙派发传单", "地推",
                5000L, 2, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2),
                "北京市朝阳区", "验收后结算");
        draftService.update(EMPLOYER, TASK_ID, req);

        Task newTask = publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0));
        newTask.setTitle("新标题");
        when(taskMapper.selectById(TASK_ID)).thenReturn(newTask);

        assertThat(service.detail(EMPLOYER, TASK_ID).title()).isEqualTo("新标题");
    }

    /** 缓存层故障不影响接口可用性：远端持续抛异常时 detail 仍正常返回。 */
    @Test
    void detailShouldStillReturnWhenCacheStoreThrows() {
        TaskDetailCache degrading = new TaskDetailCache(
                new InMemoryTaskDetailCacheStore(), new ThrowingTaskDetailCacheStore(), taskId -> { });
        TaskQueryService degradedService =
                new TaskQueryService(taskMapper, taskSlotMapper, degrading, escrowService,
                        openDisputeQuery);
        when(taskMapper.selectById(TASK_ID))
                .thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));
        when(taskSlotMapper.selectList(any()))
                .thenReturn(List.of(slot(1, SlotStatus.SLOT_AVAILABLE)));

        TaskDetailView view = degradedService.detail(EMPLOYER, TASK_ID);

        assertThat(view.id()).isEqualTo(TASK_ID);
        assertThat(view.slotOccupancy()).hasSize(1);
    }

    /** 任意操作都抛异常的缓存存储，模拟 Redis 不可用。 */
    private static final class ThrowingTaskDetailCacheStore implements TaskDetailCacheStore {

        @Override
        public Lookup get(Long taskId) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public void put(Long taskId, TaskDetailSnapshot snapshot, long ttlSeconds) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public void putMissing(Long taskId, long ttlSeconds) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public void invalidate(Long taskId) {
            throw new IllegalStateException("redis down");
        }
    }

    /** 会话详情只取任务标题与归属，不查名额、不查托管（FR-MSG.CHAT.01，补遗 §4）。 */
    @Test
    void briefOfShouldReturnTitleAndEmployerOnly() {
        when(taskMapper.selectById(TASK_ID))
                .thenReturn(publishedTask(LocalDateTime.of(2026, 11, 20, 10, 0)));

        TaskQueryService.TaskBrief brief = service.briefOf(TASK_ID);

        assertThat(brief.taskId()).isEqualTo(TASK_ID);
        assertThat(brief.title()).isEqualTo("周末小区派单");
        assertThat(brief.employerId()).isEqualTo(EMPLOYER.userId());
        assertThat(brief.publishedAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 10, 0));
        verifyNoInteractions(taskSlotMapper, escrowService);
    }

    /** 任务不存在 → 404，不返回 null 让调用方各自兜底。 */
    @Test
    void briefOfShouldThrow404WhenTaskMissing() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class, () -> service.briefOf(TASK_ID));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ==================== 辅助 ====================

    private static TaskSearchCriteria defaultCriteria() {
        return TaskSearchCriteria.from(null, null, null, null, null, null, null, null, null, null, null);
    }

    private static Page<TaskListItemRow> pageOf(List<TaskListItemRow> rows, long total) {
        Page<TaskListItemRow> page = new Page<>(1, 20);
        page.setRecords(rows);
        page.setTotal(total);
        return page;
    }

    private static TaskListItemRow row() {
        TaskListItemRow row = new TaskListItemRow();
        row.setId(TASK_ID);
        row.setTitle("周末小区派单");
        row.setCategory("地推");
        row.setUnitPrice(5000L);
        row.setWorkLocation("北京");
        row.setStartTime(LocalDateTime.of(2026, 12, 1, 9, 0));
        row.setEndTime(LocalDateTime.of(2026, 12, 2, 18, 0));
        row.setPublishedAt(LocalDateTime.of(2026, 11, 20, 10, 0));
        row.setRemainingSlots(2);
        row.setHasOpenDispute(false);
        row.setHasSettlementAbnormal(true);
        return row;
    }

    private static Task draftTask() {
        Task task = baseTask();
        task.setPublishedAt(null);
        return task;
    }

    private static Task publishedTask(LocalDateTime publishedAt) {
        Task task = baseTask();
        task.setPublishedAt(publishedAt);
        return task;
    }

    private static Task baseTask() {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setEmployerId(EMPLOYER.userId());
        task.setTitle("周末小区派单");
        task.setDescription("帮忙派发传单");
        task.setCategory("地推");
        task.setUnitPrice(5000L);
        task.setSlotCount(2);
        task.setStartTime(LocalDateTime.of(2026, 12, 1, 9, 0));
        task.setEndTime(LocalDateTime.of(2026, 12, 2, 18, 0));
        task.setWorkLocation("北京");
        task.setSettlementNote("验收后结算");
        task.setCancelledAt(null);
        task.setRecruitingClosed(false);
        return task;
    }

    private static TaskSlot slot(int seqNo, SlotStatus status) {
        TaskSlot slot = new TaskSlot();
        slot.setId(4000L + seqNo);
        slot.setTaskId(TASK_ID);
        slot.setSeqNo(seqNo);
        slot.setStatus(status);
        return slot;
    }
}
