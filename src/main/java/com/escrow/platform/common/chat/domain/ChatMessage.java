package com.escrow.platform.common.chat.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 会话消息，对应表 {@code chat_message}（V23）。
 *
 * <p><b>只追加</b>：表上没有 {@code updated_at} / {@code read_flag} / {@code deleted}，
 * 不提供编辑、撤回、删除，消息一旦落库即为证据（补遗 §2.3）。</p>
 *
 * <p>{@code contactHint} 只存 0/1 标记，不存命中原文；命中照常发送、不拦截、不改写正文。</p>
 *
 * <p>不加 {@code @Data} / {@code @Builder}：理由同 {@code Task}（docs/decisions.md #39、docs/tech-stack.md）。</p>
 *
 * @since V11
 */
@Getter
@Setter
@TableName("chat_message")
public class ChatMessage {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type。 */
    @TableId
    private Long id;

    /** 会话 ID，逻辑外键 chat_conversation.id。 */
    private Long conversationId;

    /** 发送人账号 ID；系统消息为 0。 */
    private Long senderId;

    /** 消息类型。 */
    private ChatMessageType msgType;

    /** 消息正文，1~1000 字。 */
    private String content;

    /** 疑似联系方式标记：0 否 / 1 是（只提示不拦截）。 */
    private Integer contactHint;

    /** 发送时间，数据库默认值写入。 */
    private LocalDateTime createdAt;
}
