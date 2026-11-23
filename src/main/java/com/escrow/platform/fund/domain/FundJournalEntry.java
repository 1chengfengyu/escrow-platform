package com.escrow.platform.fund.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 分录，对应表 {@code fund_journal_entry}（由 {@code V4__v2_core_tables.sql} 建好）。
 *
 * <p>只追加；同一批次 {@code SUM(DEBIT) = SUM(CREDIT)} 由记账服务在事务末尾断言，
 * 库层无延迟约束、无触发器（docs/decisions.md #52）。</p>
 *
 * <p>不加 {@code @Data}：{@code equals/hashCode} 会破坏 MyBatis 一级缓存的实体身份语义；
 * 也不加 {@code @Builder}：MyBatis-Plus 依赖无参构造（docs/decisions.md #39、docs/tech-stack.md）。</p>
 *
 * <p>状态机见 SRS 4.2.2「分录」。</p>
 *
 * @since V2
 */
@Getter
@Setter
@TableName("fund_journal_entry")
public class FundJournalEntry {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type（docs/decisions.md #14）。 */
    @TableId
    private Long id;

    /** 凭证批次 ID，逻辑外键 fund_journal_batch.id（指向主键，不是批次号）。 */
    private Long journalBatchId;

    /** 账户 ID，逻辑外键 fund_account.id；必填。 */
    private Long accountId;

    /** 科目冗余，用于重建时校验必须与 account_id 对应账户的 subject 一致。 */
    private AccountSubject subject;

    /** 方向：DEBIT（借，流出）/ CREDIT（贷，流入）。 */
    private JournalDirection direction;

    /** 发生额（分），ck_fund_journal_entry_amount 兜住 > 0 且 <= 999999999。 */
    private Long amount;

    /** 记账时间，数据库默认值写入（本表只追加，无 updatedAt）。 */
    private LocalDateTime createdAt;
}
