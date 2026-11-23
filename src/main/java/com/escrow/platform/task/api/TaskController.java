package com.escrow.platform.task.api;

import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.task.application.TaskCancelService;
import com.escrow.platform.task.application.TaskDraftService;
import com.escrow.platform.task.application.TaskExpiryService;
import com.escrow.platform.task.application.TaskPublishService;
import com.escrow.platform.task.application.TaskQueryService;
import com.escrow.platform.task.domain.TaskSearchCriteria;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务入口：草稿写入（FR-TASK.CREATE.01~.03 / .05）与检索详情（FR-TASK.LIST.01~.04、
 * FR-TASK.DETAIL.01~.02）。
 *
 * <p>Controller 只做三件事：取会话主体、转交 application 层、包 {@link ApiResponse}。
 * 「字段是否合法」「参数解析与白名单」「可见性判定」全部在领域纯函数与 Service 里；
 * 路径 {@code id} 的非法数字由全局异常处理成 400，这里不 try/catch。</p>
 *
 * <p>创建草稿不校验资质；发布才校验（FR-ACCT.QUAL.03）。发布 / 撤单 / 到期收口是 v2-c5
 * 的三个资金动作，业务全部在 application 层，这里只转发。
 * 端点都不在 {@code SecurityConfig.PUBLIC_PATHS} 里：未登录一律 401。</p>
 *
 * @since V2
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskDraftService taskDraftService;
    private final TaskQueryService taskQueryService;
    private final TaskPublishService taskPublishService;
    private final TaskCancelService taskCancelService;
    private final TaskExpiryService taskExpiryService;

    /** 创建任务草稿：200 + {@code data.id}（字符串化的雪花 ID）。 */
    @PostMapping
    public ApiResponse<TaskCreatedView> create(@RequestBody TaskDraftRequest request,
                                               @AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(new TaskCreatedView(taskDraftService.create(current, request)));
    }

    /** 全量编辑草稿 / 已发布任务的可编辑字段：200 + {@code data=null}。 */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @RequestBody TaskDraftRequest request,
                                    @AuthenticationPrincipal LoginUser current) {
        taskDraftService.update(current, id, request);
        return ApiResponse.ok(null);
    }

    /** 任务列表：200 + 分页视图（FR-TASK.LIST.01~.04）。 */
    @GetMapping
    public ApiResponse<PageView<TaskListItemView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String minPrice,
            @RequestParam(required = false) String maxPrice,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order) {
        return ApiResponse.ok(taskQueryService.list(
                TaskSearchCriteria.from(keyword, category, minPrice, maxPrice, from, to, location,
                        page, size, sort, order)));
    }

    /** 任务详情：200 + 详情视图；不存在或草稿对非雇主不可见一律 404（FR-TASK.DETAIL.01~.02）。 */
    @GetMapping("/{id}")
    public ApiResponse<TaskDetailView> detail(@PathVariable Long id,
                                              @AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(taskQueryService.detail(current, id));
    }

    /** 发布：草稿 → 招募中，同一事务内冻结托管（FR-TASK.CREATE.04、FR-ESCROW.HOLD.01）。 */
    @PostMapping("/{id}/publish")
    public ApiResponse<Void> publish(@PathVariable Long id,
                                     @AuthenticationPrincipal LoginUser current) {
        taskPublishService.publish(current, id);
        return ApiResponse.ok(null);
    }

    /** 撤单：全额退款 + 释放待占用名额 + 状态收口（FR-TASK.CANCEL.01~.02）。 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id,
                                    @AuthenticationPrincipal LoginUser current) {
        taskCancelService.cancel(current, id);
        return ApiResponse.ok(null);
    }

    /** 手动到期收口：与 {@code @Scheduled} 共用同一 Service 方法，供演示与运维触发（FR-TASK.EXPIRE.01）。 */
    @PostMapping("/{id}/expire")
    public ApiResponse<Void> expire(@PathVariable Long id,
                                    @AuthenticationPrincipal LoginUser current) {
        taskExpiryService.expireByEmployer(current, id);
        return ApiResponse.ok(null);
    }
}
