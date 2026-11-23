package com.escrow.platform.common.chat.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 会话读游标，对应表 {@code chat_read_cursor}（V23）。
 *
 * <p>未读用读游标表达，不在消息表回写已读标记：未读数 =
 * {@code id > last_read_message_id AND sender_id <> 当前用户}。</p>
 *
 * <p><b>复合主键 {@code (conversation_id, user_id)}，本实体刻意不声明 {@code @TableId}</b>：
 * MyBatis-Plus 只支持单主键，声明任何一个都会让 {@code selectById/updateById} 写错行。
 * 读写一律走 {@code ChatReadCursorMapper} 的 XML upsert。</p>
 *
 * @since V11
 */
@Getter
@Setter
@TableName("chat_read_cursor")
public class ChatReadCursor {

    /** 会话 ID，复合主键之一。 */
    private Long conversationId;

    /** 用户账号 ID，复合主键之一。 */
    private Long userId;

    /** 已读到的最后一条消息 ID；0 表示未读任何消息。 */
    private Long lastReadMessageId;
}
