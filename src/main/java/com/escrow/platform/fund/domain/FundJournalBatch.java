package com.escrow.platform.fund.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 凭证批次，对应表 {@code fund_journal_batch}（由 {@code V4__v2_core_tables.sql} 建好）。
 *
 * <p>幂等靠 {@code uk_fund_journal_batch_biz (biz_type, biz_no)}，不靠 batch_no 生成规则；
 * 写入路径必须是 INSERT → 撞唯一键 → 捕获后 SELECT 回查，禁止先查后插。</p>
 *
 * <p>本表是只追加账本：没有 updatedAt、没有 deleted、没有 status；批次内容不允许修改，只能反方向补一笔。</p>
 *
 * <p>不加 {@code @Data}：{@code equals/hashCode} 会破坏 MyBatis 一级缓存的实体身份语义；
 * 也不加 {@code @Builder}：MyBatis-Plus 依赖无参构造（docs/decisions.md #39、docs/tech-stack.md）。</p>
 *
 * <p>{@code bizType} / {@code eventType} 在 v2-c5 定稿为枚举：c1 刻意留 {@code String} 的
 * 留痕机制已按预期触发（{@code V2CoreSchemaContractTest#journalEventTypeShouldNotExistYet} 改成正向断言）。</p>
 *
 * <p>状态机见 SRS 4.2.2「凭证批次」。</p>
 *
 * @since V2
 */
@Getter
@Setter
@TableName("fund_journal_batch")
public class FundJournalBatch {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type（docs/decisions.md #14）。 */
    @TableId
    private Long id;

    /** 凭证批次号，全局唯一、生成后不变；只用于批次查询，不做幂等键。 */
    private String batchNo;

    /** 业务类型：记账入口的业务动作标识，幂等键第一段，见 {@link JournalBizType}。 */
    private JournalBizType bizType;

    /** 业务单据号：幂等键第二段；同一业务动作重放必须复用。 */
    private String bizNo;

    /** 事件类型：记账事件模板标识，见 {@link JournalEventType}。 */
    private JournalEventType eventType;

    /** 触发方：USER / SYSTEM / OPERATOR，见 {@link JournalTriggerType}。 */
    private JournalTriggerType triggerType;

    /** 凭证摘要。 */
    private String summary;

    /** 记账时间，数据库默认值写入（本表只追加，无 updatedAt）。 */
    private LocalDateTime createdAt;
}
