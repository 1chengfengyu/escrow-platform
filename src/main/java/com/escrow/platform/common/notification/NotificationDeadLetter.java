package com.escrow.platform.common.notification;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 通知死信，对应表 {@code notification_dead_letter}（V13）。
 *
 * <p>进入 DLT 的消息不再重试，必须落表让人能看到；
 * 只记主题、原文与失败原因，不尝试解析业务字段。</p>
 *
 * @since V5
 */
@Getter
@Setter
@TableName("notification_dead_letter")
public class NotificationDeadLetter {

    /** 雪花 ID。 */
    @TableId
    private Long id;

    /** 原主题。 */
    private String topic;

    /** 原始消息体。 */
    private String payload;

    /** 失败原因。 */
    private String errorMessage;

    /** 累计重放次数（V22 起）。 */
    private Integer replayCount;

    /** 最近一次重放时间（V22 起），未重放为空。 */
    private LocalDateTime lastReplayedAt;

    /** 落表时间。 */
    private LocalDateTime createdAt;
}
