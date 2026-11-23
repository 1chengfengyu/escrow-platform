package com.escrow.platform.task.api;

import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.RestAccessDeniedHandler;
import com.escrow.platform.common.security.RestAuthenticationEntryPoint;
import com.escrow.platform.common.security.SecurityConfig;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.common.web.TraceIdFilter;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.application.TaskCancelService;
import com.escrow.platform.task.application.TaskDraftService;
import com.escrow.platform.task.application.TaskExpiryService;
import com.escrow.platform.task.application.TaskPublishService;
import com.escrow.platform.task.application.TaskQueryService;
import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.TaskDisplayStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务接口契约测试（v2-c2 草稿 6 个用例 + v2-c3 检索详情 11 个用例）。
 *
 * <p>结构：{@code addFilters=false} 的 MockMvc 走业务断言；会话主体用 {@code loginAs}
 * 直接放 {@code SecurityContextHolder}。切片用 {@code @MockitoBean} 隔离
 * {@link TaskDraftService} 与 {@link TaskQueryService}，不碰持久层。</p>
 *
 * <p>参数校验走真实的 {@code TaskSearchCriteria.from}（不是 mock），所以 400 分支测到的是真逻辑。</p>
 *
 * @since V2
 */
@WebMvcTest(controllers = TaskController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        TraceIdFilter.class, JacksonConfig.class})
class TaskControllerTest {

    private static final String TASK_BODY = """
            {"title":"周末小区派单","description":"帮忙派发传单","category":"地推",
             "unitPrice":5000,"slotCount":3,
             "startTime":"2026-12-01T09:00:00","endTime":"2026-12-02T09:00:00",
             "workLocation":"北京市朝阳区","settlementNote":"验收后结算"}
            """;

    private static final LoginUser EMPLOYER =
            new LoginUser(1001L, "13800138000", UserRole.EMPLOYER, IdentityType.EMPLOYER);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskDraftService taskDraftService;

    @MockitoBean
    private TaskQueryService taskQueryService;

    @MockitoBean
    private TaskPublishService taskPublishService;

    @MockitoBean
    private TaskCancelService taskCancelService;

    @MockitoBean
    private TaskExpiryService taskExpiryService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ==================== v2-c2：创建 / 编辑 ====================

    /** 雇主创建草稿：200 + SUCCESS + data.id（雪花 ID 字符串化，防 JS 末位抹 0）。 */
    @Test
    void employerCreateTaskShouldReturnStringId() throws Exception {
        loginAs(EMPLOYER);
        when(taskDraftService.create(any(), any(TaskDraftRequest.class)))
                .thenReturn(1234567890123456789L);

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TASK_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value("1234567890123456789"));

        verify(taskDraftService).create(any(), any(TaskDraftRequest.class));
    }

    /** 非雇主身份创建：403 + FORBIDDEN。 */
    @Test
    void workerCreateTaskShouldReturnForbidden() throws Exception {
        loginAs(new LoginUser(1002L, "13900139000", UserRole.WORKER, IdentityType.WORKER));
        doThrow(new ApiException(ErrorCode.FORBIDDEN, "仅雇主身份可创建任务"))
                .when(taskDraftService).create(any(), any(TaskDraftRequest.class));

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TASK_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** 雇主编辑：200 + SUCCESS。 */
    @Test
    void employerUpdateTaskShouldReturnSuccess() throws Exception {
        loginAs(EMPLOYER);

        mockMvc.perform(put("/api/tasks/{id}", 123L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TASK_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(taskDraftService).update(any(), eq(123L), any(TaskDraftRequest.class));
    }

    /** 编辑不存在的任务：404 + RESOURCE_NOT_FOUND。 */
    @Test
    void updateMissingTaskShouldReturnNotFound() throws Exception {
        loginAs(EMPLOYER);
        doThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在"))
                .when(taskDraftService).update(any(), eq(123L), any(TaskDraftRequest.class));

        mockMvc.perform(put("/api/tasks/{id}", 123L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TASK_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /** 已发布任务改单价：409 + TASK_PRICE_LOCKED（本卡新增错误码）。 */
    @Test
    void updatePublishedPriceShouldReturnPriceLocked() throws Exception {
        loginAs(EMPLOYER);
        doThrow(new ApiException(ErrorCode.TASK_PRICE_LOCKED, "任务已发布，单价与名额数不可修改"))
                .when(taskDraftService).update(any(), eq(123L), any(TaskDraftRequest.class));

        mockMvc.perform(put("/api/tasks/{id}", 123L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TASK_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_PRICE_LOCKED"));
    }

    /** 字段不合法：400 + INVALID_PARAMETER + data.fields 指明字段。 */
    @Test
    void createInvalidFieldsShouldReturnFieldsInData() throws Exception {
        loginAs(EMPLOYER);
        doThrow(new ApiException(ErrorCode.INVALID_PARAMETER, "请求参数不合法",
                Map.of("fields", Map.of("title", "标题不能为空"))))
                .when(taskDraftService).create(any(), any(TaskDraftRequest.class));

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"帮忙派发传单\",\"category\":\"地推\",\"unitPrice\":5000,"
                                + "\"slotCount\":3,\"startTime\":\"2026-12-01T09:00:00\","
                                + "\"endTime\":\"2026-12-02T09:00:00\",\"workLocation\":\"北京市朝阳区\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.data.fields.title").value("标题不能为空"));
    }

    // ==================== v2-c3：列表 ====================

    /** 无参列表：默认 page=1 / size=20，Long 与 total 字符串化。 */
    @Test
    void listWithoutParamsShouldUseDefaults() throws Exception {
        loginAs(EMPLOYER);
        when(taskQueryService.list(any())).thenReturn(PageView.of(List.of(itemView()), 1, 20, 1));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value("1"))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("2000001"))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value("5000"));
    }

    /** 全部参数齐上：200（关键字 / 类别 / 价格 / 时间 / 地点 / 分页 / 排序）。 */
    @Test
    void listWithAllParamsShouldReturnOk() throws Exception {
        loginAs(EMPLOYER);
        when(taskQueryService.list(any())).thenReturn(PageView.of(List.of(), 2, 5, 0));

        mockMvc.perform(get("/api/tasks")
                        .param("keyword", "周末")
                        .param("category", "地推")
                        .param("minPrice", "100")
                        .param("maxPrice", "9000")
                        .param("from", "2026-12-01T09:00:00")
                        .param("to", "2026-12-02T18:00:00")
                        .param("location", "北京")
                        .param("page", "2")
                        .param("size", "5")
                        .param("sort", "unit_price")
                        .param("order", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(5));
    }

    /** size=101：400 + INVALID_PARAMETER + data.fields.size。 */
    @Test
    void listWithSize101ShouldReturn400() throws Exception {
        loginAs(EMPLOYER);

        mockMvc.perform(get("/api/tasks").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.data.fields.size").exists());
    }

    /** page=0：400 + data.fields.page。 */
    @Test
    void listWithPageZeroShouldReturn400() throws Exception {
        loginAs(EMPLOYER);

        mockMvc.perform(get("/api/tasks").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.data.fields.page").exists());
    }

    /** sort 白名单外：400 + data.fields.sort。 */
    @Test
    void listWithUnknownSortShouldReturn400() throws Exception {
        loginAs(EMPLOYER);

        mockMvc.perform(get("/api/tasks").param("sort", "created_at"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.data.fields.sort").exists());
    }

    /** from 非 ISO 格式：400 + data.fields.from。 */
    @Test
    void listWithMalformedFromShouldReturn400() throws Exception {
        loginAs(EMPLOYER);

        mockMvc.perform(get("/api/tasks").param("from", "2026/12/01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.data.fields.from").exists());
    }

    /** 空结果：200 + items=[]、total="0"、totalPages=0。 */
    @Test
    void listEmptyShouldReturnEmptyItems() throws Exception {
        loginAs(EMPLOYER);
        when(taskQueryService.list(any())).thenReturn(PageView.of(List.of(), 1, 20, 0));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value("0"))
                .andExpect(jsonPath("$.data.totalPages").value(0));
    }

    // ==================== v2-c3：详情 ====================

    /** 当事人详情：200，带名额明细；escrowAmount 在 c3 不出现。 */
    @Test
    void detailForPartyShouldIncludeSlotOccupancy() throws Exception {
        loginAs(EMPLOYER);
        when(taskQueryService.detail(any(), eq(2000001L))).thenReturn(detailView(true));

        mockMvc.perform(get("/api/tasks/{id}", 2000001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("2000001"))
                .andExpect(jsonPath("$.data.partyView").value(true))
                .andExpect(jsonPath("$.data.slotOccupancy.length()").value(2))
                .andExpect(jsonPath("$.data.slotOccupancy[0].seqNo").value(1))
                .andExpect(jsonPath("$.data.slotOccupancy[0].status").value("SLOT_AVAILABLE"))
                .andExpect(jsonPath("$.data.escrowAmount").doesNotExist());
    }

    /** 非当事人详情：200，但没有 slotOccupancy 与 escrowAmount 两个键。 */
    @Test
    void detailForNonPartyShouldHideSensitiveKeys() throws Exception {
        loginAs(new LoginUser(2002L, "13900139000", UserRole.WORKER, IdentityType.WORKER));
        when(taskQueryService.detail(any(), eq(2000001L))).thenReturn(detailView(false));

        mockMvc.perform(get("/api/tasks/{id}", 2000001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.partyView").value(false))
                .andExpect(jsonPath("$.data.slotOccupancy").doesNotExist())
                .andExpect(jsonPath("$.data.escrowAmount").doesNotExist());
    }

    /** 任务不存在：404 + RESOURCE_NOT_FOUND。 */
    @Test
    void detailMissingTaskShouldReturn404() throws Exception {
        loginAs(EMPLOYER);
        when(taskQueryService.detail(any(), eq(2000001L)))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在"));

        mockMvc.perform(get("/api/tasks/{id}", 2000001L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("任务不存在"));
    }

    /** 草稿对非雇主本人：404（不暴露存在性）。 */
    @Test
    void detailDraftForNonOwnerShouldReturn404() throws Exception {
        loginAs(new LoginUser(2002L, "13900139000", UserRole.WORKER, IdentityType.WORKER));
        when(taskQueryService.detail(any(), eq(3028L)))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在"));

        mockMvc.perform(get("/api/tasks/{id}", 3028L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // ==================== v2-c5：发布 / 撤单 / 到期 ====================

    /** 发布成功：200 + SUCCESS，业务全部在 application 层。 */
    @Test
    void employerPublishShouldReturnSuccess() throws Exception {
        loginAs(EMPLOYER);

        mockMvc.perform(post("/api/tasks/{id}/publish", 2000001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(taskPublishService).publish(any(), eq(2000001L));
    }

    /** 余额不足：409 + INSUFFICIENT_BALANCE。 */
    @Test
    void publishWithoutBalanceShouldReturn409() throws Exception {
        loginAs(EMPLOYER);
        doThrow(new ApiException(ErrorCode.INSUFFICIENT_BALANCE, "可用余额不足"))
                .when(taskPublishService).publish(any(), eq(2000001L));

        mockMvc.perform(post("/api/tasks/{id}/publish", 2000001L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
    }

    /** 有活跃名额时撤单：409 + TASK_HAS_ACTIVE_SLOT。 */
    @Test
    void cancelWithActiveSlotShouldReturn409() throws Exception {
        loginAs(EMPLOYER);
        doThrow(new ApiException(ErrorCode.TASK_HAS_ACTIVE_SLOT, "任务存在进行中的名额，不可撤单"))
                .when(taskCancelService).cancel(any(), eq(2000001L));

        mockMvc.perform(post("/api/tasks/{id}/cancel", 2000001L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_HAS_ACTIVE_SLOT"));
    }

    /** 未到期手动收口：409 + INVALID_STATE_TRANSITION。 */
    @Test
    void expireBeforeEndTimeShouldReturn409() throws Exception {
        loginAs(EMPLOYER);
        doThrow(new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "任务尚未到期，无法收口"))
                .when(taskExpiryService).expireByEmployer(any(), eq(2000001L));

        mockMvc.perform(post("/api/tasks/{id}/expire", 2000001L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    // ==================== 辅助 ====================

    private static TaskListItemView itemView() {
        return new TaskListItemView(2000001L, "周末小区派单", "地推", 5000L, "北京",
                LocalDateTime.of(2026, 12, 1, 9, 0),
                LocalDateTime.of(2026, 12, 2, 18, 0),
                LocalDateTime.of(2026, 11, 20, 10, 0),
                2, false, false);
    }

    private static TaskDetailView detailView(boolean party) {
        List<TaskDetailView.SlotItem> slots = party
                ? List.of(new TaskDetailView.SlotItem(1, SlotStatus.SLOT_AVAILABLE),
                        new TaskDetailView.SlotItem(2, SlotStatus.SLOT_OCCUPIED))
                : null;
        return new TaskDetailView(2000001L, "周末小区派单", "帮忙派发传单", "地推", 5000L, 2,
                LocalDateTime.of(2026, 12, 1, 9, 0),
                LocalDateTime.of(2026, 12, 2, 18, 0),
                "北京", "验收后结算",
                LocalDateTime.of(2026, 11, 20, 10, 0),
                null, 1, slots, null, party, false, false,
                TaskDisplayStatus.TASK_RECRUITING, false);
    }

    private static void loginAs(LoginUser principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
