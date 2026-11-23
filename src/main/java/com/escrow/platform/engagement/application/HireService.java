package com.escrow.platform.engagement.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.common.chat.application.ChatEventDispatcher;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.engagement.domain.ApplicationStateMachine;
import com.escrow.platform.engagement.domain.ApplicationStatus;
import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.engagement.domain.EngagementApplication;
import com.escrow.platform.engagement.domain.EngagementContract;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementApplicationMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementContractMapper;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.task.application.TaskParticipationService;
import com.escrow.platform.task.application.TaskSlotOccupancyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 录用与签约（FR-SIGN.HIRE.01~.04、FR-SIGN.APPLY.08 满员收口）。
 *
 * <p><b>同一本地事务内完成三件事</b>：报名 {@code APPLY_PENDING -> APPLY_HIRED}、
 * 名额 {@code SLOT_AVAILABLE -> SLOT_OCCUPIED}、新建 {@code CONTRACT_ACTIVE} 签约，
 * 随后回填名额的签约 ID；任一步 0 行都抛业务码并由事务整体回滚。</p>
 *
 * <p><b>写顺序固定</b>：条件更新报名 → 原子占名额 → 插签约 → 回填名额签约 ID → 满员收口。
 * 报名先行是为了让「同一报名重复录用」在第二步之前就能被识别为幂等，而不是报
 * {@code SLOT_TAKEN}（卡片 §2.3.8 / §2.3.9）。</p>
 *
 * <p><b>满员不写 {@code recruiting_closed}</b>（FR-SIGN.HIRE.02）：满员只让任务从可报名
 * 列表消失，判据是「不存在待占用名额」；该字段只由撤单与到期置真，任务状态由读取侧聚合。</p>
 *
 * <p><b>错误码</b>：非本任务雇主 403；报名不存在 404；自我录用 {@code SELF_HIRE_FORBIDDEN}；
 * 名额被抢光 {@code SLOT_TAKEN}；报名不是待处理 {@code INVALID_STATE_TRANSITION}
 * （不自创 {@code ALREADY_HIRED}）。</p>
 *
 * @since V3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HireService {

    private static final String NOT_EMPLOYER_MESSAGE = "仅雇主可录用报名者";
    private static final String NOT_OWNER_MESSAGE = "无权录用他人任务的报名者";
    private static final String APPLICATION_NOT_FOUND_MESSAGE = "报名不存在";
    private static final String SELF_HIRE_MESSAGE = "不能录用自己";
    private static final String CANCELLED_MESSAGE = "任务已撤单，无法录用";
    private static final String ENDED_MESSAGE = "任务已过结束时间，无法录用";
    private static final String NOT_PENDING_MESSAGE = "该报名当前状态不可录用";
    private static final String SLOT_TAKEN_MESSAGE = "名额已被占用，请刷新后重试";
    private static final String CONTRACT_MISSING_MESSAGE = "签约记录缺失，请稍后重试";

    private final EngagementApplicationMapper applicationMapper;
    private final EngagementContractMapper contractMapper;
    private final TaskParticipationService taskParticipationService;
    private final TaskSlotOccupancyService taskSlotOccupancyService;
    private final ApplicationRejectionService applicationRejectionService;
    /** 私聊事件（CARD-10）：事务提交后建 CONTRACT 会话，失败不影响录用。 */
    private final ChatEventDispatcher chatEventDispatcher;

    /**
     * 雇主录用某报名者（FR-SIGN.HIRE.01~.04）。
     *
     * @param current       会话主体，必须是该任务的雇主
     * @param taskId        任务 ID
     * @param applicationId 报名 ID
     * @return 签约 ID；重复录用同一报名时返回既有签约 ID（幂等）
     * @throws ApiException 403 身份 / 归属；404 任务或报名不存在；409 自我录用 /
     *                      名额已满 / 报名非待处理 / 任务已撤单或已截止
     */
    @Transactional
    public Long hire(LoginUser current, Long taskId, Long applicationId) {
        requireEmployer(current);

        // 任务行排他锁：与撤单 / 到期同一锁序，避免并发录用之间以及录用与收口之间死锁
        TaskParticipationService.ParticipationFacts facts = taskParticipationService.lockFactsOf(taskId);
        if (!facts.employerId().equals(current.userId())) {
            log.warn("越权录用 taskId={} userId={}", taskId, current.userId());
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_OWNER_MESSAGE);
        }
        if (facts.cancelledAt() != null) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, CANCELLED_MESSAGE);
        }
        if (!LocalDateTime.now().isBefore(facts.endTime())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, ENDED_MESSAGE);
        }

        EngagementApplication application = applicationMapper.selectById(applicationId);
        if (application == null || !application.getTaskId().equals(taskId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, APPLICATION_NOT_FOUND_MESSAGE);
        }
        if (application.getWorkerId().equals(facts.employerId())) {
            throw new ApiException(ErrorCode.SELF_HIRE_FORBIDDEN, SELF_HIRE_MESSAGE);
        }
        if (application.getStatus() == ApplicationStatus.APPLY_HIRED) {
            return existingContractId(applicationId);
        }
        if (application.getStatus() == ApplicationStatus.APPLY_REJECTED) {
            // 撤单 / 到期已在上面按任务事实拦掉，此处只可能是满员自动拒绝（APPLY.08）
            throw new ApiException(ErrorCode.SLOT_TAKEN, SLOT_TAKEN_MESSAGE);
        }
        if (application.getStatus() != ApplicationStatus.APPLY_PENDING) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, NOT_PENDING_MESSAGE);
        }

        ApplicationStatus hired = ApplicationStateMachine.transit(ApplicationStatus.APPLY_PENDING,
                ApplicationStateMachine.Event.HIRE);
        int claimed = applicationMapper.update(null, new LambdaUpdateWrapper<EngagementApplication>()
                .eq(EngagementApplication::getId, applicationId)
                .eq(EngagementApplication::getTaskId, taskId)
                .eq(EngagementApplication::getStatus, ApplicationStatus.APPLY_PENDING)
                .set(EngagementApplication::getStatus, hired));
        if (claimed != 1) {
            // 锁内不应发生；兜底按最新状态给出幂等或业务码，绝不返回 5xx
            EngagementApplication latest = applicationMapper.selectById(applicationId);
            if (latest != null && latest.getStatus() == ApplicationStatus.APPLY_HIRED) {
                return existingContractId(applicationId);
            }
            if (latest != null && latest.getStatus() == ApplicationStatus.APPLY_REJECTED) {
                throw new ApiException(ErrorCode.SLOT_TAKEN, SLOT_TAKEN_MESSAGE);
            }
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, NOT_PENDING_MESSAGE);
        }

        Long slotId = taskSlotOccupancyService.occupyForHire(taskId, applicationId);

        EngagementContract contract = new EngagementContract();
        contract.setTaskId(taskId);
        contract.setSlotId(slotId);
        contract.setApplicationId(applicationId);
        contract.setWorkerId(application.getWorkerId());
        contract.setStatus(ContractStatus.CONTRACT_ACTIVE);
        contract.setEffectiveAt(LocalDateTime.now());
        try {
            contractMapper.insert(contract);
        } catch (DuplicateKeyException ex) {
            // uk_engagement_contract_active_slot / uk_engagement_contract_application 兜底
            log.warn("签约唯一键冲突 taskId={} slotId={} applicationId={}",
                    taskId, slotId, applicationId);
            throw new ApiException(ErrorCode.SLOT_TAKEN, SLOT_TAKEN_MESSAGE);
        }

        taskSlotOccupancyService.bindContract(slotId, contract.getId());

        int rejected = 0;
        if (!taskSlotOccupancyService.hasAvailableSlot(taskId)) {
            rejected = applicationRejectionService.rejectPendingOfTask(taskId);
        }
        log.info("录用完成 taskId={} applicationId={} workerId={} slotId={} contractId={} rejected={}",
                taskId, applicationId, application.getWorkerId(), slotId, contract.getId(), rejected);
        chatEventDispatcher.contractCreated(taskId, contract.getId(),
                facts.employerId(), application.getWorkerId());
        return contract.getId();
    }

    /**
     * 同一报名重复录用时回查既有签约（卡片 §2.3.8）。
     *
     * @param applicationId 报名 ID
     * @return 既有签约 ID
     * @throws ApiException 报名已录用但签约缺失（数据不一致，409 {@code CONCURRENT_MODIFICATION}）
     */
    private Long existingContractId(Long applicationId) {
        EngagementContract existing = contractMapper.selectOne(
                new LambdaQueryWrapper<EngagementContract>()
                        .eq(EngagementContract::getApplicationId, applicationId));
        if (existing == null) {
            log.warn("报名已录用但签约缺失 applicationId={}", applicationId);
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, CONTRACT_MISSING_MESSAGE);
        }
        return existing.getId();
    }

    private static void requireEmployer(LoginUser current) {
        if (current == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "请先登录");
        }
        if (current.currentIdentity() != IdentityType.EMPLOYER) {
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_EMPLOYER_MESSAGE);
        }
    }
}
