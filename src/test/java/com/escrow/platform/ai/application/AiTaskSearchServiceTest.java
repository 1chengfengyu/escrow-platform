package com.escrow.platform.ai.application;

import com.escrow.platform.ai.domain.QuerySlots;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.api.TaskListItemView;
import com.escrow.platform.task.application.TaskQueryService;
import com.escrow.platform.task.domain.TaskSearchCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * 劳动者找活（V12.1）：槽位语义反转、草稿过期、周末过滤。
 *
 * @since V12.1
 */
@ExtendWith(MockitoExtension.class)
class AiTaskSearchServiceTest {

    private static final LoginUser WORKER =
            new LoginUser(3001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Mock
    private TaskQueryService taskQueryService;
    @Mock
    private AiTraceService traceService;

    private AiDraftStore draftStore;
    private AiTaskSearchService service;

    @BeforeEach
    void setUp() {
        draftStore = new AiDraftStore(300);
        service = new AiTaskSearchService(draftStore, taskQueryService, traceService);
        lenient().when(traceService.record(anyLong(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn("t-task-1");
    }

    /** 雇主的「预算上限」必须反转成找活的「报价下限」，理由与证据字段可核。 */
    @Test
    void v12c1PriceMaxBecomesUnitPriceFloor() {
        stubRows(
                row(9001L, "短视频拍摄", "拍摄", 60000L, "北京",
                        at(2026, 11, 25, 9), at(2026, 11, 26, 18), at(2026, 11, 20, 10), 3),
                row(9002L, "文案写作", "文案", 30000L, "北京",
                        at(2026, 11, 27, 9), at(2026, 11, 28, 18), at(2026, 11, 19, 10), 2));
        String draftId = draftStore.save(
                new QuerySlots("拍摄", 50000L, "北京", null, 3, List.of(), 80), "想找北京报酬 500 以上的拍摄活");

        AiResults.TaskSearch result = service.search(WORKER, draftId, List.of());

        TaskSearchCriteria criteria = capturedCriteria();
        assertThat(criteria.minPrice()).isEqualTo(50000L);
        assertThat(criteria.maxPrice()).isNull();
        assertThat(criteria.location()).isEqualTo("北京");
        assertThat(criteria.keyword()).isEqualTo("拍摄");

        assertThat(result.items()).extracting(AiResults.TaskSearchItem::taskId).containsExactly(9001L, 9002L);
        AiResults.TaskSearchItem top = result.items().get(0);
        assertThat(top.reason()).contains("报酬 600.00 元/名额", "不低于你的期望下限 500.00 元");
        assertThat(top.evidenceFields()).contains("unitPrice", "workLocation", "publishedAt");
        assertThat(result.strategy()).isEqualTo("WEIGHTED");
        assertThat(result.traceNo()).isEqualTo("t-task-1");
    }

    /** headcount 在找活里没有对应字段：只带人数的草稿不应产出任何筛选条件。 */
    @Test
    void v12c1HeadcountIsDropped() {
        stubRows(row(9001L, "展会协助", "展会", 20000L, "上海",
                at(2026, 11, 25, 9), at(2026, 11, 25, 18), at(2026, 11, 20, 10), 5));
        String draftId = draftStore.save(
                new QuerySlots(null, null, null, null, 5, List.of(), 40), "招 5 个人");

        AiResults.TaskSearch result = service.search(WORKER, draftId, List.of());

        TaskSearchCriteria criteria = capturedCriteria();
        assertThat(criteria.keyword()).isNull();
        assertThat(criteria.minPrice()).isNull();
        assertThat(criteria.maxPrice()).isNull();
        assertThat(criteria.location()).isNull();
        assertThat(result.items()).hasSize(1);
    }

    /** 草稿不存在或过期：转 404，让用户重新解析。 */
    @Test
    void v12c1UnknownDraftRejected() {
        assertThatThrownBy(() -> service.search(WORKER, "not-exists", List.of()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** weekend 从「要求对方周末可接」反转为「任务时间窗覆盖周末」。 */
    @Test
    void v12c1WeekendFiltersByTaskWindow() {
        stubRows(
                row(9001L, "周末布展", "展会", 40000L, "北京",
                        at(2026, 11, 20, 9), at(2026, 11, 21, 18), at(2026, 11, 20, 10), 2),
                row(9002L, "工作日值守", "值守", 40000L, "北京",
                        at(2026, 11, 23, 9), at(2026, 11, 24, 18), at(2026, 11, 20, 10), 2));
        String draftId = draftStore.save(
                new QuerySlots(null, null, "北京", true, null, List.of(), 60), "周末能干");

        AiResults.TaskSearch result = service.search(WORKER, draftId, List.of());

        assertThat(result.items()).extracting(AiResults.TaskSearchItem::taskId).containsExactly(9001L);
        assertThat(result.items().get(0).evidenceFields()).contains("startTime", "endTime");
    }

    private void stubRows(TaskListItemView... rows) {
        lenient().when(taskQueryService.list(any()))
                .thenReturn(PageView.of(List.of(rows), 1, 50, rows.length));
    }

    private TaskSearchCriteria capturedCriteria() {
        ArgumentCaptor<TaskSearchCriteria> captor = ArgumentCaptor.forClass(TaskSearchCriteria.class);
        verify(taskQueryService).list(captor.capture());
        return captor.getValue();
    }

    private TaskListItemView row(long id, String title, String category, long unitPrice, String location,
                                 LocalDateTime start, LocalDateTime end, LocalDateTime publishedAt,
                                 int remainingSlots) {
        return new TaskListItemView(id, title, category, unitPrice, location, start, end, publishedAt,
                remainingSlots, false, false);
    }

    private LocalDateTime at(int year, int month, int day, int hour) {
        return LocalDateTime.of(year, month, day, hour, 0);
    }
}
