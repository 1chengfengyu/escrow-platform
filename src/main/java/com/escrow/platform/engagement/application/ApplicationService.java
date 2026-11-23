package com.escrow.platform.engagement.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.chat.application.ChatEventDispatcher;
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
import com.escrow.platform.task.application.TaskParticipationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 报名（FR-SIGN.APPLY.01~.07）。
 *
 * <p><b>守卫顺序固定</b>：身份 → 任务存在 → 不能报自己的任务 → 非草稿 → 未撤单 → 未过结束时间
 * → 存在待占用名额 → 插入。前七步只读，最后一步靠唯一索引兜住并发重复报名；
 * 禁止「先 SELECT 判断再 INSERT」（project_rules §6.2.8）。</p>
 *
 * <p><b>报名不占名额</b>：本类不写 {@code task_slot}，占用发生在录用（FR-SIGN.APPLY.04）。</p>
 *
 * <p><b>数据权限</b>：报名者列表只允许该任务的雇主查看；劳动者与其他雇主一律 403。
 * 任务归属通过 task 域 {@link TaskParticipationService} 读取，不直查 task 表。</p>
 *
 * @since V3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationService {

    private static final String TASK_NOT_FOUND_MESSAGE = "任务不存在";

    private final EngagementApplicationMapper applicationMapper;
    /** 跨域只走 task 域 Service 入口（project_rules §3）。 */
    private final TaskParticipationService taskParticipationService;
    /** 跨域只走 identity 域 Service 入口，且只取公开昵称。 */
    private final IdentityProfileService identityProfileService;
    /** 私聊事件（CARD-10）：事务提交后建 APPLICATION 会话，失败不影响报名。 */
    private final ChatEventDispatcher chatEventDispatcher;

    /**
     * 劳动者报名任务（FR-SIGN.APPLY.01~.05）。
     *
     * @param current 会话主体
     * @param taskId  任务 ID
     * @return 新建报名记录 ID
     * @throws ApiException 非劳动者 403、任务不存在 404、自己的任务 409、
     *                      已撤单 / 已截止 409、无名额 409、重复报名 409
     */
    public Long apply(LoginUser current, Long taskId) {
        requireWorker(current);

        TaskParticipationService.ParticipationFacts facts = taskParticipationService.factsOf(taskId);
        if (facts.employerId().equals(current.userId())) {
            throw new ApiException(ErrorCode.SELF_APPLICATION_FORBIDDEN, "不能报名自己发布的任务");
        }
        // 草稿对非雇主不可见：返回 404 而不是 409，避免泄露任务存在性
        if (facts.publishedAt() == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, TASK_NOT_FOUND_MESSAGE);
        }
        if (facts.cancelledAt() != null) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "任务已撤单，无法报名");
        }
        if (!LocalDateTime.now().isBefore(facts.endTime())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "任务已过报名截止时间");
        }
        if (facts.availableSlots() <= 0) {
            throw new ApiException(ErrorCode.NO_SLOT_AVAILABLE, "剩余名额不足，无法报名");
        }

        EngagementApplication application = new EngagementApplication();
        application.setTaskId(taskId);
        application.setWorkerId(current.userId());
        application.setStatus(ApplicationStatus.APPLY_PENDING);
        try {
            applicationMapper.insert(application);
        } catch (DuplicateKeyException ex) {
            // 唯一索引 (active_task_id, active_worker_id) 命中：并发重复报名只可能一笔成功
            log.info("重复报名被拒 taskId={} workerId={}", taskId, current.userId());
            throw new ApiException(ErrorCode.ALREADY_APPLIED, "已报名该任务，请勿重复提交");
        }
        log.info("报名创建 taskId={} workerId={} applicationId={}",
                taskId, current.userId(), application.getId());
        chatEventDispatcher.applicationCreated(taskId, application.getId(),
                facts.employerId(), current.userId());
        return application.getId();
    }

    /**
     * 雇主按报名时间倒序分页查看本任务报名者（FR-SIGN.APPLY.07）。
     *
     * @param current 会话主体
     * @param taskId  任务 ID
     * @param query   已校验的分页参数
     * @return 报名者分页视图；昵称来自 identity 域，未签约只给公开字段
     * @throws ApiException 非雇主 403、任务不存在 404、非本任务雇主 403
     */
    @Transactional(readOnly = true)
    public PageView<ApplicationListItemView> list(LoginUser current, Long taskId, ApplicationPageQuery query) {
        requireEmployer(current);
        TaskParticipationService.ParticipationFacts facts = taskParticipationService.factsOf(taskId);
        if (!facts.employerId().equals(current.userId())) {
            log.warn("越权查看报名者 taskId={} userId={}", taskId, current.userId());
            throw new ApiException(ErrorCode.FORBIDDEN, "无权查看该任务的报名者");
        }

        Page<EngagementApplication> request = new Page<>(query.page(), query.size());
        Page<EngagementApplication> result = applicationMapper.selectPage(request,
                new LambdaQueryWrapper<EngagementApplication>()
                        .eq(EngagementApplication::getTaskId, taskId)
                        .orderByDesc(EngagementApplication::getAppliedAt)
                        .orderByDesc(EngagementApplication::getId));

        List<Long> workerIds = result.getRecords().stream()
                .map(EngagementApplication::getWorkerId)
                .distinct()
                .toList();
        Map<Long, String> nicknames = identityProfileService.nicknames(workerIds);
        List<ApplicationListItemView> items = result.getRecords().stream()
                .map(application -> new ApplicationListItemView(
                        application.getId(),
                        application.getWorkerId(),
                        nicknames.getOrDefault(application.getWorkerId(), ""),
                        application.getStatus(),
                        application.getAppliedAt()))
                .toList();
        return PageView.of(items, query.page(), query.size(), result.getTotal());
    }

    private static void requireWorker(LoginUser current) {
        if (current == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "请先登录");
        }
        if (current.currentIdentity() != IdentityType.WORKER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅劳动者身份可报名");
        }
    }

    private static void requireEmployer(LoginUser current) {
        if (current == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "请先登录");
        }
        if (current.currentIdentity() != IdentityType.EMPLOYER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅雇主可查看报名者");
        }
    }
}
