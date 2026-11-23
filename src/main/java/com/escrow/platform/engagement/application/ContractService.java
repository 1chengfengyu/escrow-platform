package com.escrow.platform.engagement.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.engagement.domain.ApplicationStateMachine;
import com.escrow.platform.engagement.domain.ApplicationStatus;
import com.escrow.platform.engagement.domain.ContractStateMachine;
import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.engagement.domain.DeliverableSubmission;
import com.escrow.platform.engagement.domain.EngagementApplication;
import com.escrow.platform.engagement.domain.EngagementContract;
import com.escrow.platform.engagement.domain.EngagementDeliverable;
import com.escrow.platform.engagement.domain.PerformanceEventType;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementApplicationMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementContractMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementDeliverableMapper;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.task.application.TaskSlotOccupancyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 履约的开始与取消（FR-SIGN.START.01~.02、FR-SIGN.CANCEL.01~.04、FR-JOB.START.01）。
 *
 * <p><b>三写一致性</b>：签约状态、名额状态、报名状态在同一本地事务内推进：
 * 开始 = 签约「生效中 → 履约中」+ 名额「已占用 → 履约中」；
 * 取消 = 签约「生效中 → 已取消」+ 名额「已占用 → 待占用」并清空报名 / 签约 ID
 * + 报名「已录用 → 已撤回」。</p>
 *
 * <p><b>并发与幂等</b>：线性化点是签约行的条件更新（{@code WHERE id=? AND status='CONTRACT_ACTIVE'}）。
 * 开始与取消（含系统自动取消）互相竞争同一行，只有一个能抢到；
 * 抢不到的一律返回「当前状态不允许该操作」（409），绝不出现「已开始又取消」。</p>
 *
 * <p><b>取消不搬钱</b>：名额回到「待占用」，托管明细保持「已托管」，不触发退款
 * （FR-SIGN.CANCEL.01 / START.02）；也不得把名额置为「已释放」（FR-SIGN.CANCEL.04）。</p>
 *
 * @since V3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractService {

    /** 单次扫描最多处理的签约数（避免一次事务扫全表）。 */
    private static final int SCAN_LIMIT = 100;

    private static final String NOT_WORKER_MESSAGE = "仅劳动者身份可操作签约";
    private static final String CONTRACT_NOT_FOUND_MESSAGE = "签约不存在";
    private static final String NOT_OWNER_MESSAGE = "无权操作他人签约";
    private static final String NOT_STARTABLE_MESSAGE = "当前签约不可开始履约";
    private static final String NOT_CANCELLABLE_MESSAGE = "当前签约不可取消（已开始或已结束）";
    private static final String NOT_DELIVERABLE_MESSAGE = "当前签约不可交付（未开始履约或已交付）";
    private static final String DELIVERABLE_EXISTS_MESSAGE = "该签约已提交交付物，不能重复提交";

    private final EngagementContractMapper contractMapper;
    private final EngagementApplicationMapper applicationMapper;
    private final EngagementDeliverableMapper deliverableMapper;
    private final TaskSlotOccupancyService taskSlotOccupancyService;
    private final PerformanceRecordService performanceRecordService;
    private final ObjectMapper objectMapper;

    /** 签约生效后未开始履约的挂起时长（FR-SIGN.START.02）；默认 24 小时，配置项见 CR-13。 */
    @Value("${escrow.contract.pending-start-hours:24}")
    private long pendingStartHours;

    /**
     * 劳动者开始履约（FR-SIGN.START.01）。
     *
     * @param current    会话主体，必须是该签约的劳动者
     * @param contractId 签约 ID
     * @return 跃迁后的状态 {@link ContractStatus#CONTRACT_STARTED}
     * @throws ApiException 401 未登录；403 身份 / 归属；404 签约不存在；409 签约不在「生效中」
     */
    @Transactional
    public ContractStatus start(LoginUser current, Long contractId) {
        requireWorker(current);
        EngagementContract contract = requireOwnedContract(current, contractId);
        if (contract.getStatus() != ContractStatus.CONTRACT_ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, NOT_STARTABLE_MESSAGE);
        }

        ContractStatus started = ContractStateMachine.transit(ContractStatus.CONTRACT_ACTIVE,
                ContractStateMachine.Event.START);
        LocalDateTime now = LocalDateTime.now();
        int claimed = contractMapper.update(null, new LambdaUpdateWrapper<EngagementContract>()
                .eq(EngagementContract::getId, contractId)
                .eq(EngagementContract::getWorkerId, current.userId())
                .eq(EngagementContract::getStatus, ContractStatus.CONTRACT_ACTIVE)
                .set(EngagementContract::getStatus, started)
                .set(EngagementContract::getStartedAt, now));
        if (claimed != 1) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, NOT_STARTABLE_MESSAGE);
        }

        taskSlotOccupancyService.startPerformance(contract.getTaskId(), contract.getSlotId());
        performanceRecordService.record(contract.getWorkerId(), contractId,
                PerformanceEventType.PERFORMANCE_START, now);
        log.info("开始履约 contractId={} taskId={} slotId={} workerId={}",
                contractId, contract.getTaskId(), contract.getSlotId(), contract.getWorkerId());
        return started;
    }

    /**
     * 劳动者提交交付物（FR-DELIVER.SUBMIT.01~.03）。
     *
     * <p>前置状态必须是「履约中」：未开始就交付没有履约过程（必须先 START）；
     * 成功后签约与名额同时进入「已交付」，写 {@code delivered_at} 作为验收倒计时起点。</p>
     *
     * @param current    会话主体，必须是该签约的劳动者
     * @param contractId 签约 ID
     * @param submission 已校验的提交内容
     * @return 跃迁后的状态 {@link ContractStatus#CONTRACT_DELIVERED}
     * @throws ApiException 401 未登录；403 身份 / 归属；404 签约不存在；
     *                      409 未开始 / 已交付 / 重复提交
     */
    @Transactional
    public ContractStatus deliver(LoginUser current, Long contractId, DeliverableSubmission submission) {
        requireWorker(current);
        EngagementContract contract = requireOwnedContract(current, contractId);
        if (contract.getStatus() != ContractStatus.CONTRACT_STARTED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, NOT_DELIVERABLE_MESSAGE);
        }

        ContractStatus delivered = ContractStateMachine.transit(contract.getStatus(),
                ContractStateMachine.Event.DELIVER);
        LocalDateTime now = LocalDateTime.now();
        int claimed = contractMapper.update(null, new LambdaUpdateWrapper<EngagementContract>()
                .eq(EngagementContract::getId, contractId)
                .eq(EngagementContract::getWorkerId, current.userId())
                .eq(EngagementContract::getStatus, ContractStatus.CONTRACT_STARTED)
                .set(EngagementContract::getStatus, delivered)
                .set(EngagementContract::getDeliveredAt, now));
        if (claimed != 1) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, NOT_DELIVERABLE_MESSAGE);
        }

        taskSlotOccupancyService.deliverForContract(contract.getTaskId(), contract.getSlotId());
        insertDeliverable(contract, submission, now);
        performanceRecordService.record(contract.getWorkerId(), contractId,
                PerformanceEventType.PERFORMANCE_DELIVER, now);
        log.info("提交交付物 contractId={} taskId={} slotId={} workerId={} images={}",
                contractId, contract.getTaskId(), contract.getSlotId(), contract.getWorkerId(),
                submission.imageUrls().size());
        return delivered;
    }

    /** 交付物只追加一次；撞 {@code UNIQUE(contract_id)} 说明状态与数据不一致，按 409 返回。 */
    private void insertDeliverable(EngagementContract contract, DeliverableSubmission submission,
                                   LocalDateTime now) {
        EngagementDeliverable deliverable = new EngagementDeliverable();
        deliverable.setContractId(contract.getId());
        deliverable.setWorkerId(contract.getWorkerId());
        deliverable.setImageUrls(writeImages(submission));
        deliverable.setDescription(submission.description());
        deliverable.setSubmittedAt(now);
        try {
            deliverableMapper.insert(deliverable);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, DELIVERABLE_EXISTS_MESSAGE);
        }
    }

    private String writeImages(DeliverableSubmission submission) {
        try {
            return objectMapper.writeValueAsString(submission.imageUrls());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("交付物图片列表序列化失败 contractId="
                    + submission.imageUrls().size(), ex);
        }
    }

    /**
     * 劳动者取消签约（FR-SIGN.CANCEL.01~.04）。
     *
     * <p>只允许从「生效中」取消：已开始履约 / 已交付必须走申诉，不得单方取消（FR-SIGN.CANCEL.03）。</p>
     *
     * @param current    会话主体，必须是该签约的劳动者
     * @param contractId 签约 ID
     * @return 跃迁后的状态 {@link ContractStatus#CONTRACT_CANCELLED}
     * @throws ApiException 401 未登录；403 身份 / 归属；404 签约不存在；409 已开始 / 已结束
     */
    @Transactional
    public ContractStatus cancel(LoginUser current, Long contractId) {
        requireWorker(current);
        EngagementContract contract = requireOwnedContract(current, contractId);
        if (contract.getStatus() != ContractStatus.CONTRACT_ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, NOT_CANCELLABLE_MESSAGE);
        }
        if (!cancelContract(contract, PerformanceEventType.PERFORMANCE_CANCEL, LocalDateTime.now())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, NOT_CANCELLABLE_MESSAGE);
        }
        log.info("取消签约 contractId={} taskId={} slotId={} workerId={}",
                contractId, contract.getTaskId(), contract.getSlotId(), contract.getWorkerId());
        return ContractStatus.CONTRACT_CANCELLED;
    }

    /**
     * 扫描生效超过挂起时长仍未开始的签约 ID（FR-JOB.START.01）。
     *
     * @return 最多 {@value #SCAN_LIMIT} 个签约 ID
     */
    public List<Long> findOverdueContractIds() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(pendingStartHours);
        List<EngagementContract> contracts = contractMapper.selectList(
                new LambdaQueryWrapper<EngagementContract>()
                        .select(EngagementContract::getId)
                        .eq(EngagementContract::getStatus, ContractStatus.CONTRACT_ACTIVE)
                        .le(EngagementContract::getEffectiveAt, cutoff)
                        .last("LIMIT " + SCAN_LIMIT));
        return contracts.stream().map(EngagementContract::getId).toList();
    }

    /**
     * 未开始自动取消（FR-SIGN.START.02）：与手动取消共用 {@link #cancelContract}，
     * 区别只在履约记录事件类型与返回值语义。
     *
     * @param contractId 签约 ID
     * @return {@code true} 本次完成自动取消；{@code false} 已开始 / 已取消 / 未超时 / 不存在
     */
    @Transactional
    public boolean autoCancel(Long contractId) {
        EngagementContract contract = contractMapper.selectById(contractId);
        if (contract == null || contract.getStatus() != ContractStatus.CONTRACT_ACTIVE) {
            return false;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusHours(pendingStartHours);
        if (contract.getEffectiveAt() == null || contract.getEffectiveAt().isAfter(cutoff)) {
            return false;
        }
        if (!cancelContract(contract, PerformanceEventType.PERFORMANCE_TIMEOUT_CANCEL,
                LocalDateTime.now())) {
            return false;
        }
        log.info("未开始自动取消 contractId={} taskId={} slotId={} workerId={}",
                contractId, contract.getTaskId(), contract.getSlotId(), contract.getWorkerId());
        return true;
    }

    /**
     * 争议裁决放款：签约「已交付 → 已完成」（V6，FR-DISPUTE.RESOLVE.01）。
     *
     * <p>裁决放款等同管理员替雇主验收，复用 {@code ACCEPT} 事件，不新造状态。</p>
     *
     * @param contractId 签约 ID
     */
    @Transactional
    public void completeAfterArbitration(Long contractId) {
        transitDelivered(contractId, ContractStateMachine.Event.ACCEPT, "争议裁决放款");
    }

    /**
     * 争议裁决退款：签约「已交付 → 已取消」（V6，FR-DISPUTE.RESOLVE.01）。
     *
     * @param contractId 签约 ID
     */
    @Transactional
    public void cancelAfterArbitration(Long contractId) {
        transitDelivered(contractId, ContractStateMachine.Event.ARBITRATION_CANCEL, "争议裁决退款");
    }

    /**
     * 争议撤回后重新起算 48h 验收倒计时（V6，FR-DISPUTE.WITHDRAW.01）。
     *
     * <p>自动验收 Job 以 {@code delivered_at} 为截止点，撤回时把它刷成当前时间，
     * 否则撤回即被立刻自动验收。</p>
     *
     * @param contractId 签约 ID
     */
    @Transactional
    public void restartAcceptanceCountdown(Long contractId) {
        LocalDateTime now = LocalDateTime.now();
        int updated = contractMapper.update(null, new LambdaUpdateWrapper<EngagementContract>()
                .eq(EngagementContract::getId, contractId)
                .eq(EngagementContract::getStatus, ContractStatus.CONTRACT_DELIVERED)
                .set(EngagementContract::getDeliveredAt, now)
                .set(EngagementContract::getUpdatedAt, now));
        if (updated != 1) {
            throw new IllegalStateException(
                    "签约不在已交付，无法重新起算验收倒计时 contractId=" + contractId);
        }
        log.info("争议撤回后重新起算验收倒计时 contractId={}", contractId);
    }

    private void transitDelivered(Long contractId, ContractStateMachine.Event event, String action) {
        LocalDateTime now = LocalDateTime.now();
        int updated = contractMapper.update(null, new LambdaUpdateWrapper<EngagementContract>()
                .eq(EngagementContract::getId, contractId)
                .eq(EngagementContract::getStatus, ContractStatus.CONTRACT_DELIVERED)
                .set(EngagementContract::getStatus,
                        ContractStateMachine.transit(ContractStatus.CONTRACT_DELIVERED, event))
                .set(EngagementContract::getUpdatedAt, now));
        if (updated != 1) {
            throw new IllegalStateException(action + "失败，签约不在已交付 contractId=" + contractId);
        }
        log.info("{} contractId={}", action, contractId);
    }

    /**
     * 取消的统一实现：抢签约 → 名额回待占用 → 报名撤回 → 记履约记录。
     *
     * @return {@code false} 表示签约已被别的路径推进（并发开始 / 并发取消），本次不做任何写
     */
    private boolean cancelContract(EngagementContract contract, PerformanceEventType eventType,
                                   LocalDateTime now) {
        ContractStatus cancelled = ContractStateMachine.transit(ContractStatus.CONTRACT_ACTIVE,
                ContractStateMachine.Event.CANCEL);
        int claimed = contractMapper.update(null, new LambdaUpdateWrapper<EngagementContract>()
                .eq(EngagementContract::getId, contract.getId())
                .eq(EngagementContract::getStatus, ContractStatus.CONTRACT_ACTIVE)
                .set(EngagementContract::getStatus, cancelled));
        if (claimed != 1) {
            return false;
        }

        taskSlotOccupancyService.reopenAfterCancel(contract.getTaskId(), contract.getSlotId());
        withdrawApplication(contract.getApplicationId());
        performanceRecordService.record(contract.getWorkerId(), contract.getId(), eventType, now);
        return true;
    }

    /** 报名「已录用 → 已撤回」，与名额清空报名 ID 在同一事务（A.3.7）。 */
    private void withdrawApplication(Long applicationId) {
        ApplicationStatus withdrawn = ApplicationStateMachine.transit(ApplicationStatus.APPLY_HIRED,
                ApplicationStateMachine.Event.WITHDRAW);
        int updated = applicationMapper.update(null, new LambdaUpdateWrapper<EngagementApplication>()
                .eq(EngagementApplication::getId, applicationId)
                .eq(EngagementApplication::getStatus, ApplicationStatus.APPLY_HIRED)
                .set(EngagementApplication::getStatus, withdrawn));
        if (updated != 1) {
            throw new IllegalStateException(
                    "报名状态与签约不一致，无法撤回 applicationId=" + applicationId);
        }
    }

    private EngagementContract requireOwnedContract(LoginUser current, Long contractId) {
        EngagementContract contract = contractMapper.selectById(contractId);
        if (contract == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, CONTRACT_NOT_FOUND_MESSAGE);
        }
        if (!contract.getWorkerId().equals(current.userId())) {
            log.warn("越权操作签约 contractId={} userId={}", contractId, current.userId());
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_OWNER_MESSAGE);
        }
        return contract;
    }

    /**
     * 劳动者身份守卫；{@code ContractQueryService} 的「我的签约」复用同一判定。
     */
    static void requireWorker(LoginUser current) {
        if (current == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "请先登录");
        }
        if (current.currentIdentity() != IdentityType.WORKER) {
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_WORKER_MESSAGE);
        }
    }
}
