package com.escrow.platform.fund.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.fund.domain.AccountSubject;
import com.escrow.platform.fund.domain.FundJournalBatch;
import com.escrow.platform.fund.domain.FundJournalEntry;
import com.escrow.platform.fund.domain.JournalBizType;
import com.escrow.platform.fund.domain.JournalDirection;
import com.escrow.platform.fund.domain.JournalEventType;
import com.escrow.platform.fund.domain.JournalTriggerType;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalBatchMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalEntryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 唯一记账入口（SRS 3.4.1、docs/decisions.md #52）。
 *
 * <p>全系统只有本类可以 INSERT {@code fund_journal_batch} / {@code fund_journal_entry}；
 * 其它 Service 只能调 {@link #post}。理由：借贷平衡无法由 MySQL 延迟约束兜底，
 * 只有把所有写入收口到一处，提交前的 {@code Σ借 = Σ贷} 断言才有意义。</p>
 *
 * <p>幂等：{@code uk_fund_journal_batch_biz (biz_type, biz_no)}；写入路径固定为
 * INSERT → 撞唯一键 → 捕获后 SELECT 回查，禁止先查后插（并发下会写出两个批次）。
 * 命中已存在批次时直接返回它，不重复写分录。</p>
 *
 * <p>事务边界由调用方决定：本类方法为 {@code REQUIRED}，加入调用方事务。</p>
 *
 * @since V2
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JournalService {

    /** 分录金额上限，与 {@code ck_fund_journal_entry_amount} 一致。 */
    private static final long MAX_ENTRY_AMOUNT = 999_999_999L;

    /** 业务单据号列宽 {@code VARCHAR(32)}。 */
    private static final int MAX_BIZ_NO_LENGTH = 32;

    private final FundJournalBatchMapper fundJournalBatchMapper;
    private final FundJournalEntryMapper fundJournalEntryMapper;

    /**
     * 一条待记分录。
     *
     * @param accountId 账户 ID
     * @param subject   科目（必须与账户科目一致）
     * @param direction 方向
     * @param amount    发生额（分），必须 &gt; 0
     */
    public record EntryDraft(Long accountId, AccountSubject subject,
                             JournalDirection direction, long amount) {
    }

    /**
     * 写入一个凭证批次及其分录。
     *
     * @param bizType     业务类型（幂等键第一段）
     * @param bizNo       业务单据号（幂等键第二段），≤32 字符
     * @param eventType   事件模板
     * @param triggerType 触发方
     * @param summary     摘要
     * @param entries     分录草稿，借方合计必须等于贷方合计
     * @return 本次写入的批次；已存在同幂等键时返回既有批次
     * @throws IllegalArgumentException 入参不合法或借贷不平（调用方缺陷，不是业务失败）
     */
    @Transactional
    public FundJournalBatch post(JournalBizType bizType, String bizNo, JournalEventType eventType,
                                 JournalTriggerType triggerType, String summary,
                                 List<EntryDraft> entries) {
        validate(bizType, bizNo, eventType, triggerType, summary, entries);

        FundJournalBatch batch = new FundJournalBatch();
        batch.setBatchNo(UUID.randomUUID().toString().replace("-", ""));
        batch.setBizType(bizType);
        batch.setBizNo(bizNo);
        batch.setEventType(eventType);
        batch.setTriggerType(triggerType);
        batch.setSummary(summary);

        try {
            fundJournalBatchMapper.insert(batch);
        } catch (DuplicateKeyException e) {
            FundJournalBatch existing = fundJournalBatchMapper.selectOne(
                    new LambdaQueryWrapper<FundJournalBatch>()
                            .eq(FundJournalBatch::getBizType, bizType)
                            .eq(FundJournalBatch::getBizNo, bizNo));
            if (existing == null) {
                // 撞的可能是 batch_no 唯一键而不是业务幂等键：回查不到就原样抛出，
                // 绝不能返回 null 让调用方误以为记账成功
                throw e;
            }
            log.info("凭证批次已存在，按幂等返回 bizType={} bizNo={}", bizType, bizNo);
            return existing;
        }

        for (EntryDraft draft : entries) {
            FundJournalEntry entry = new FundJournalEntry();
            entry.setJournalBatchId(batch.getId());
            entry.setAccountId(draft.accountId());
            entry.setSubject(draft.subject());
            entry.setDirection(draft.direction());
            entry.setAmount(draft.amount());
            fundJournalEntryMapper.insert(entry);
        }
        return batch;
    }

    /**
     * 提交前校验：幂等键可落库、金额合法、借贷相等。
     *
     * <p>借贷不相等是程序员错误（分录模板写错），抛 {@link IllegalArgumentException}
     * 让事务回滚并暴露为 500，绝不能记成业务失败。</p>
     */
    private static void validate(JournalBizType bizType, String bizNo, JournalEventType eventType,
                                 JournalTriggerType triggerType, String summary,
                                 List<EntryDraft> entries) {
        if (bizType == null || eventType == null || triggerType == null) {
            throw new IllegalArgumentException("凭证批次的类型 / 事件 / 触发方不能为空");
        }
        if (bizNo == null || bizNo.isBlank() || bizNo.length() > MAX_BIZ_NO_LENGTH) {
            throw new IllegalArgumentException("业务单据号必须在 1~32 字符之间");
        }
        if (summary == null || summary.isBlank() || summary.length() > 255) {
            throw new IllegalArgumentException("凭证摘要必须在 1~255 字符之间");
        }
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("凭证批次至少需要一条分录");
        }

        long debit = 0L;
        long credit = 0L;
        for (EntryDraft entry : entries) {
            if (entry.accountId() == null || entry.subject() == null || entry.direction() == null) {
                throw new IllegalArgumentException("分录的账户 / 科目 / 方向不能为空");
            }
            if (entry.amount() <= 0 || entry.amount() > MAX_ENTRY_AMOUNT) {
                throw new IllegalArgumentException("分录金额必须在 1~999999999 之间");
            }
            if (entry.direction() == JournalDirection.DEBIT) {
                debit = Math.addExact(debit, entry.amount());
            } else {
                credit = Math.addExact(credit, entry.amount());
            }
        }
        if (debit != credit) {
            throw new IllegalArgumentException("凭证批次借贷不平：借 " + debit + " / 贷 " + credit);
        }
    }
}
