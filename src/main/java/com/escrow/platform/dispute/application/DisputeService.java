package com.escrow.platform.dispute.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.dispute.api.DisputeEvidenceRequest;
import com.escrow.platform.dispute.api.DisputeEvidenceView;
import com.escrow.platform.dispute.api.DisputeSummaryView;
import com.escrow.platform.dispute.api.DisputeView;
import com.escrow.platform.dispute.domain.Dispute;
import com.escrow.platform.dispute.domain.DisputeEvidence;
import com.escrow.platform.dispute.domain.DisputeInitiatorType;
import com.escrow.platform.dispute.domain.DisputeResolution;
import com.escrow.platform.dispute.domain.DisputeStateMachine;
import com.escrow.platform.dispute.domain.DisputeStatus;
import com.escrow.platform.dispute.infrastructure.mapper.DisputeEvidenceMapper;
import com.escrow.platform.dispute.infrastructure.mapper.DisputeMapper;
import com.escrow.platform.engagement.application.ContractQueryService;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.task.application.TaskQueryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 争议单读写（V4 建单；V6 补撤回、裁决、证据与查询）。
 *
 * <p>一个名额可有多张历史争议单；撤回后再次发起复用同一张（状态回「待裁决」，
 * 撤回次数已累加），因此「当前是否有未决争议」始终按 {@code status='DISPUTE_PENDING'} 判定。</p>
 *
 * <p>资金与跨域状态推进在 {@link DisputeTxService}，本类只写争议域自己的表。</p>
 *
 * @since V4
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeService {

    static final String DEFAULT_REJECT_REASON = "雇主拒收交付物";
    static final String DEFAULT_APPEAL_REASON = "劳动者申诉交付不公";

    private static final int MAX_WITHDRAW_COUNT = 2;
    private static final int MAX_LIST_SIZE = 100;
    private static final String DISPUTE_NOT_FOUND_MESSAGE = "争议单不存在";
    private static final String NOT_PARTY_MESSAGE = "无权查看或操作该争议单";

    private final DisputeMapper disputeMapper;
    private final DisputeEvidenceMapper disputeEvidenceMapper;
    private final ContractQueryService contractQueryService;
    private final TaskQueryService taskQueryService;
    private final ObjectMapper objectMapper;

    /**
     * 雇主拒收开争议（V4 入口，保持原语义）。
     *
     * @param slotId 名额 ID
     * @param reason 拒收理由；空则用默认理由
     * @return 争议单 ID（可能是复用撤回后的旧单）
     */
    @Transactional
    public Long openEmployerReject(Long slotId, String reason) {
        return openOrReuse(slotId, DisputeInitiatorType.EMPLOYER, reason);
    }

    /**
     * 开启或复用争议单（V6，FR-DISPUTE.OPEN.01 / WITHDRAW.02）。
     *
     * <p>该名额存在「已撤回」争议单时复用之：状态重置为待裁决、发起方与理由更新，
     * 撤回次数保留；否则插入新单。</p>
     *
     * @param slotId    名额 ID
     * @param initiator 发起方
     * @param reason    争议理由
     * @return 争议单 ID
     */
    @Transactional
    public Long openOrReuse(Long slotId, DisputeInitiatorType initiator, String reason) {
        if (hasOpenDispute(slotId)) {
            throw new ApiException(ErrorCode.DISPUTE_ACTIVE, "该名额已有未决争议");
        }
        Dispute withdrawn = disputeMapper.selectOne(new LambdaQueryWrapper<Dispute>()
                .eq(Dispute::getSlotId, slotId)
                .eq(Dispute::getStatus, DisputeStatus.DISPUTE_WITHDRAWN)
                .orderByDesc(Dispute::getId)
                .last("LIMIT 1"));
        if (withdrawn != null) {
            int updated = disputeMapper.update(null, new LambdaUpdateWrapper<Dispute>()
                    .eq(Dispute::getId, withdrawn.getId())
                    .eq(Dispute::getStatus, DisputeStatus.DISPUTE_WITHDRAWN)
                    .set(Dispute::getStatus, DisputeStateMachine.transit(
                            DisputeStatus.DISPUTE_WITHDRAWN, DisputeStateMachine.Event.REOPEN))
                    .set(Dispute::getInitiatorType, initiator)
                    .set(Dispute::getReason, normalizeReason(reason, initiator)));
            if (updated != 1) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                        "争议单已被并发推进，请刷新后重试");
            }
            log.info("争议复用已撤回单 slotId={} disputeId={} initiator={}",
                    slotId, withdrawn.getId(), initiator);
            return withdrawn.getId();
        }

        Dispute dispute = new Dispute();
        dispute.setSlotId(slotId);
        dispute.setInitiatorType(initiator);
        dispute.setReason(normalizeReason(reason, initiator));
        dispute.setStatus(DisputeStatus.DISPUTE_PENDING);
        dispute.setWithdrawCount(0);
        disputeMapper.insert(dispute);
        log.info("争议已开启 slotId={} disputeId={} initiator={}",
                slotId, dispute.getId(), initiator);
        return dispute.getId();
    }

    /**
     * 撤回争议（V6，FR-DISPUTE.WITHDRAW.01）：只能由发起方操作，累计不超过 2 次。
     *
     * @param dispute 争议单
     * @return 撤回后的争议单
     * @throws ApiException 409 状态不允许或撤回次数已用尽
     */
    @Transactional
    public Dispute withdraw(Dispute dispute) {
        int updated = disputeMapper.update(null, new LambdaUpdateWrapper<Dispute>()
                .eq(Dispute::getId, dispute.getId())
                .eq(Dispute::getStatus, DisputeStatus.DISPUTE_PENDING)
                .lt(Dispute::getWithdrawCount, MAX_WITHDRAW_COUNT)
                .set(Dispute::getStatus, DisputeStateMachine.transit(
                        DisputeStatus.DISPUTE_PENDING, DisputeStateMachine.Event.WITHDRAW))
                .setSql("withdraw_count = withdraw_count + 1"));
        if (updated != 1) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "争议不在待裁决或撤回次数已用尽，无法撤回");
        }
        Dispute withdrawn = disputeMapper.selectById(dispute.getId());
        log.info("争议已撤回 disputeId={} withdrawCount={}",
                dispute.getId(), withdrawn.getWithdrawCount());
        return withdrawn;
    }

    /**
     * 管理员裁决落库（V6，FR-DISPUTE.CLOSE.01）。
     *
     * @param disputeId 争议单 ID
     * @param resolution 裁决结论
     * @param basis      裁决依据
     * @param resolverId 裁决人账号 ID
     * @throws ApiException 409 不在待裁决
     */
    @Transactional
    public void markResolved(Long disputeId, DisputeResolution resolution, String basis,
                             Long resolverId) {
        LocalDateTime now = LocalDateTime.now();
        int updated = disputeMapper.update(null, new LambdaUpdateWrapper<Dispute>()
                .eq(Dispute::getId, disputeId)
                .eq(Dispute::getStatus, DisputeStatus.DISPUTE_PENDING)
                .set(Dispute::getStatus, DisputeStateMachine.transit(
                        DisputeStatus.DISPUTE_PENDING, DisputeStateMachine.Event.RESOLVE))
                .set(Dispute::getResolution, resolution)
                .set(Dispute::getResolutionBasis, basis)
                .set(Dispute::getResolverId, resolverId)
                .set(Dispute::getResolvedAt, now));
        if (updated != 1) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "争议不在待裁决，无法裁决");
        }
        log.info("争议已裁决 disputeId={} resolution={} resolverId={}",
                disputeId, resolution, resolverId);
    }

    /**
     * 名额当前是否有未决争议（只读）。
     *
     * @param slotId 名额 ID
     * @return 存在 {@code DISPUTE_PENDING} 争议返回 true
     */
    public boolean hasOpenDispute(Long slotId) {
        Long count = disputeMapper.selectCount(new LambdaQueryWrapper<Dispute>()
                .eq(Dispute::getSlotId, slotId)
                .eq(Dispute::getStatus, DisputeStatus.DISPUTE_PENDING));
        return count != null && count > 0;
    }

    /**
     * 待裁决争议数量（V7 治理看板用，只读）。
     *
     * @return {@code status = DISPUTE_PENDING} 的行数
     */
    public long countPending() {
        Long count = disputeMapper.selectCount(new LambdaQueryWrapper<Dispute>()
                .eq(Dispute::getStatus, DisputeStatus.DISPUTE_PENDING));
        return count == null ? 0L : count;
    }

    /**
     * 读取争议单，不存在抛 404。
     *
     * @param disputeId 争议单 ID
     * @return 争议单
     */
    public Dispute require(Long disputeId) {
        Dispute dispute = disputeMapper.selectById(disputeId);
        if (dispute == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, DISPUTE_NOT_FOUND_MESSAGE);
        }
        return dispute;
    }

    /**
     * 争议详情：当事人双方或管理员可见（FR-DISPUTE.EVIDENCE.01「对方可见」）。
     *
     * @param current   当前登录用户
     * @param disputeId 争议单 ID
     * @return 详情（含证据）
     */
    public DisputeView detail(LoginUser current, Long disputeId) {
        Dispute dispute = require(disputeId);
        if (!isAdmin(current) && !isParty(current, dispute)) {
            log.warn("越权查看争议 disputeId={} userId={}", disputeId, current.userId());
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_PARTY_MESSAGE);
        }
        return new DisputeView(dispute.getId(), dispute.getSlotId(), dispute.getInitiatorType(),
                dispute.getReason(), dispute.getStatus(), dispute.getResolution(),
                dispute.getResolutionBasis(), dispute.getResolverId(), dispute.getResolvedAt(),
                dispute.getWithdrawCount(), dispute.getCreatedAt(), evidenceOf(disputeId));
    }

    /**
     * 管理端争议列表（可按状态过滤）。
     *
     * @param current 当前登录用户，必须是管理员
     * @param status  状态过滤，空表示全部
     * @return 列表，最多 {@value #MAX_LIST_SIZE} 条
     */
    public List<DisputeSummaryView> listForAdmin(LoginUser current, DisputeStatus status) {
        requireAdmin(current);
        LambdaQueryWrapper<Dispute> wrapper = new LambdaQueryWrapper<Dispute>()
                .orderByAsc(Dispute::getId)
                .last("LIMIT " + MAX_LIST_SIZE);
        if (status != null) {
            wrapper.eq(Dispute::getStatus, status);
        }
        return disputeMapper.selectList(wrapper).stream().map(this::summaryOf).toList();
    }

    /**
     * 超期待裁决列表（FR-DISPUTE.CLOSE.02：7 天未处置提示管理员并计入统计）。
     *
     * @param current 当前登录用户，必须是管理员
     * @param days    超期天数阈值
     * @return 超期争议，等待时间从长到短
     */
    public List<DisputeSummaryView> overdue(LoginUser current, int days) {
        requireAdmin(current);
        return overdueForSystem(days);
    }

    /**
     * 超期查询的系统口径（定时任务复用，不走会话权限）。
     *
     * @param days 超期天数阈值
     * @return 超期争议，等待时间从长到短
     */
    public List<DisputeSummaryView> overdueForSystem(int days) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, days));
        return disputeMapper.selectList(new LambdaQueryWrapper<Dispute>()
                        .eq(Dispute::getStatus, DisputeStatus.DISPUTE_PENDING)
                        .le(Dispute::getCreatedAt, cutoff)
                        .orderByAsc(Dispute::getCreatedAt)
                        .last("LIMIT " + MAX_LIST_SIZE))
                .stream().map(this::summaryOf).toList();
    }

    /**
     * 提交证据（FR-DISPUTE.EVIDENCE.01）：双方都可提交，对方可见；仅待裁决阶段可提交。
     *
     * @param current   当前登录用户
     * @param disputeId 争议单 ID
     * @param request   证据内容
     * @return 新证据视图
     * @throws ApiException 403 非当事人 / 409 不在待裁决 / 400 内容为空
     */
    @Transactional
    public DisputeEvidenceView addEvidence(LoginUser current, Long disputeId,
                                           DisputeEvidenceRequest request) {
        Dispute dispute = require(disputeId);
        if (!isParty(current, dispute)) {
            log.warn("越权提交争议证据 disputeId={} userId={}", disputeId, current.userId());
            throw new ApiException(ErrorCode.FORBIDDEN, NOT_PARTY_MESSAGE);
        }
        if (dispute.getStatus() != DisputeStatus.DISPUTE_PENDING) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "争议不在待裁决，无法提交证据");
        }
        boolean hasContent = request.content() != null && !request.content().isBlank();
        boolean hasImages = request.imageUrls() != null && !request.imageUrls().isEmpty();
        if (!hasContent && !hasImages) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "文字与图片至少提交一项");
        }

        DisputeEvidence evidence = new DisputeEvidence();
        evidence.setDisputeId(disputeId);
        evidence.setSubmitterId(current.userId());
        evidence.setSubmitterType(current.currentIdentity() == IdentityType.EMPLOYER
                ? DisputeInitiatorType.EMPLOYER : DisputeInitiatorType.WORKER);
        evidence.setContent(hasContent ? request.content().trim() : null);
        evidence.setImageUrls(hasImages ? toJson(request.imageUrls()) : null);
        evidence.setCreatedAt(LocalDateTime.now());
        disputeEvidenceMapper.insert(evidence);
        log.info("争议证据已提交 disputeId={} evidenceId={} submitterId={}",
                disputeId, evidence.getId(), current.userId());
        return evidenceViewOf(evidence);
    }

    /**
     * 争议是否属于当前用户：劳动者看签约，雇主看任务归属。
     *
     * @param current 当前登录用户
     * @param dispute 争议单
     * @return 属于当事人返回 true
     */
    public boolean isParty(LoginUser current, Dispute dispute) {
        if (current == null || current.userId() == null) {
            return false;
        }
        ContractQueryService.ContractBrief contract =
                contractQueryService.contractOfSlot(dispute.getSlotId());
        if (contract == null) {
            return false;
        }
        if (contract.workerId().equals(current.userId())) {
            return true;
        }
        Long employerId = taskQueryService.employerIdOf(contract.taskId());
        return employerId != null && employerId.equals(current.userId());
    }

    /**
     * 管理员判定（与打款处置台同一口径：只认会话当前身份）。
     *
     * @param current 当前登录用户
     * @return 管理员返回 true
     */
    public static boolean isAdmin(LoginUser current) {
        return current != null && current.currentIdentity() == IdentityType.ADMIN;
    }

    private static void requireAdmin(LoginUser current) {
        if (!isAdmin(current)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅管理员可查看争议列表");
        }
    }

    private List<DisputeEvidenceView> evidenceOf(Long disputeId) {
        return disputeEvidenceMapper.selectList(new LambdaQueryWrapper<DisputeEvidence>()
                        .eq(DisputeEvidence::getDisputeId, disputeId)
                        .orderByAsc(DisputeEvidence::getCreatedAt)
                        .orderByAsc(DisputeEvidence::getId))
                .stream().map(this::evidenceViewOf).toList();
    }

    private DisputeEvidenceView evidenceViewOf(DisputeEvidence evidence) {
        return new DisputeEvidenceView(evidence.getId(), evidence.getDisputeId(),
                evidence.getSubmitterId(), evidence.getSubmitterType(), evidence.getContent(),
                fromJson(evidence.getImageUrls()), evidence.getCreatedAt());
    }

    private DisputeSummaryView summaryOf(Dispute dispute) {
        long pendingDays = dispute.getCreatedAt() == null ? 0
                : ChronoUnit.DAYS.between(dispute.getCreatedAt(), LocalDateTime.now());
        return new DisputeSummaryView(dispute.getId(), dispute.getSlotId(),
                dispute.getInitiatorType(), dispute.getReason(), dispute.getStatus(),
                dispute.getWithdrawCount(), dispute.getCreatedAt(), pendingDays);
    }

    private String toJson(List<String> imageUrls) {
        try {
            return objectMapper.writeValueAsString(imageUrls);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("图片 URL 序列化失败", ex);
        }
    }

    private List<String> fromJson(String imageUrls) {
        if (imageUrls == null || imageUrls.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(imageUrls, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            log.warn("争议证据图片 URL 解析失败，按空列表处理 imageUrls={}", imageUrls);
            return List.of();
        }
    }

    private String normalizeReason(String reason, DisputeInitiatorType initiator) {
        if (reason == null || reason.isBlank()) {
            return initiator == DisputeInitiatorType.EMPLOYER
                    ? DEFAULT_REJECT_REASON : DEFAULT_APPEAL_REASON;
        }
        String trimmed = reason.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
    }
}
