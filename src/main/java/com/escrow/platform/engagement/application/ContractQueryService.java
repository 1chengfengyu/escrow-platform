package com.escrow.platform.engagement.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.engagement.api.ContractListItemView;
import com.escrow.platform.engagement.domain.ContractPageQuery;
import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.engagement.domain.EngagementContract;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementContractMapper;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 「我的签约」查询（数据权限第一层，NFR-SEC.02 当事人级）。
 *
 * <p>接口不接受「查谁」的参数：查询条件永远带 {@code worker_id = 当前用户}，
 * 他人签约在 SQL 层就查不出来，不依赖返回后再过滤。</p>
 *
 * @since V3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractQueryService {

    private final EngagementContractMapper contractMapper;

    /** 单次扫描上限，避免一次把全部超期签约读进内存。 */
    private static final int SCAN_LIMIT = 100;

    /**
     * 扫描交付超过阈值仍未验收的签约 ID（FR-ACCEPT.AUTO.01）。
     *
     * @param cutoff 交付时间截止点（早于该时间即超期）
     * @param limit  最多返回数量（1~{@value #SCAN_LIMIT}）
     * @return 签约 ID 列表，按交付时间升序
     */
    public List<Long> findOverdueDeliveredContractIds(java.time.LocalDateTime cutoff, int limit) {
        int safe = Math.max(1, Math.min(SCAN_LIMIT, limit));
        List<EngagementContract> contracts = contractMapper.selectList(
                new LambdaQueryWrapper<EngagementContract>()
                        .select(EngagementContract::getId)
                        .eq(EngagementContract::getStatus, ContractStatus.CONTRACT_DELIVERED)
                        .le(EngagementContract::getDeliveredAt, cutoff)
                        .orderByAsc(EngagementContract::getDeliveredAt)
                        .last("LIMIT " + safe));
        return contracts.stream().map(EngagementContract::getId).toList();
    }

    /**
     * 签约摘要（跨域只读）：打款结算需要劳动者与任务归属，但不得直查签约表。
     *
     * @param contractId 签约 ID
     * @param taskId     任务 ID
     * @param slotId     名额 ID
     * @param workerId   劳动者用户 ID
     */
    public record ContractBrief(Long contractId, Long taskId, Long slotId, Long workerId) {
    }

    /**
     * 按名额读取签约摘要（只读）。
     *
     * @param slotId 名额 ID
     * @return 签约摘要；不存在返回 {@code null}
     */
    public ContractBrief contractOfSlot(Long slotId) {
        EngagementContract contract = contractMapper.selectOne(
                new LambdaQueryWrapper<EngagementContract>()
                        .eq(EngagementContract::getSlotId, slotId));
        if (contract == null) {
            return null;
        }
        return new ContractBrief(contract.getId(), contract.getTaskId(),
                contract.getSlotId(), contract.getWorkerId());
    }

    /**
     * 按签约 ID 读取签约摘要（只读，V6 争议申诉需要）。
     *
     * @param contractId 签约 ID
     * @return 签约摘要；不存在返回 {@code null}
     */
    public ContractBrief contractOf(Long contractId) {
        EngagementContract contract = contractMapper.selectById(contractId);
        if (contract == null) {
            return null;
        }
        return new ContractBrief(contract.getId(), contract.getTaskId(),
                contract.getSlotId(), contract.getWorkerId());
    }

    /**
     * 分页查询当前劳动者的签约，按生效时间倒序。
     *
     * @param current 会话主体，必须是劳动者
     * @param query   已校验的分页参数
     * @return 当前页签约
     */
    @Transactional(readOnly = true)
    public PageView<ContractListItemView> listMine(LoginUser current, ContractPageQuery query) {
        ContractService.requireWorker(current);

        Page<EngagementContract> result = contractMapper.selectPage(
                new Page<>(query.page(), query.size()),
                new LambdaQueryWrapper<EngagementContract>()
                        .eq(EngagementContract::getWorkerId, current.userId())
                        .orderByDesc(EngagementContract::getEffectiveAt)
                        .orderByDesc(EngagementContract::getId));

        List<ContractListItemView> items = result.getRecords().stream()
                .map(ContractQueryService::toView)
                .toList();
        log.info("查询我的签约 workerId={} page={} size={} total={}",
                current.userId(), query.page(), query.size(), result.getTotal());
        return PageView.of(items, query.page(), query.size(), result.getTotal());
    }

    private static ContractListItemView toView(EngagementContract contract) {
        return new ContractListItemView(contract.getId(), contract.getTaskId(), contract.getSlotId(),
                contract.getStatus(), contract.getEffectiveAt(), contract.getStartedAt(),
                contract.getDeliveredAt());
    }
}
