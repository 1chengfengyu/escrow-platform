package com.escrow.platform.common.chat.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 任务内会话，对应表 {@code chat_conversation}（V23）。
 *
 * <p>三类 scope 共用本表，{@code scope + biz_id} 区分来源；{@code INQUIRY} 的
 * {@code bizId} 恒为 {@code 0}——MySQL 唯一索引中 {@code NULL} 不参与去重，
 * 用 {@code 0} 才能让咨询会话的唯一键真正生效。</p>
 *
 * <p>会话不依赖签约：{@code INQUIRY} 在报名前即可开启（补遗 §2.1）。</p>
 *
 * <p>不加 {@code @Data} / {@code @Builder}：理由同 {@code Task}（docs/decisions.md #39、docs/tech-stack.md）。</p>
 *
 * @since V11
 */
@Getter
@Setter
@TableName("chat_conversation")
public class ChatConversation {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type。 */
    @TableId
    private Long id;

    /** 会话范围；与唯一键一起决定「同一范围同一业务对象只有一条会话」。 */
    private ChatScope scope;

    /** 任务 ID，逻辑外键 task.id；不 join task 表。 */
    private Long taskId;

    /** 范围业务 ID：APPLICATION = 报名 ID / CONTRACT = 签约 ID；INQUIRY 固定 0。 */
    private Long bizId;

    /** 雇主账号 ID，逻辑外键 platform_user.id。 */
    private Long employerId;

    /** 劳动者账号 ID，逻辑外键 platform_user.id。 */
    private Long workerId;

    /** 发起人账号 ID，须为双方之一（应用层校验）。 */
    private Long initiatorId;

    /** 会话状态；新建恒为 {@link ChatConversationStatus#OPEN}。 */
    private ChatConversationStatus status;

    /** 最后消息时间，用于会话列表排序；无消息时为 {@code null}。 */
    private LocalDateTime lastMessageAt;

    /** 创建时间，数据库默认值写入。 */
    private LocalDateTime createdAt;
}
