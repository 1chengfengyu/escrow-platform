package com.escrow.platform.common.notification;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 站内信，对应表 {@code notification}（V13）。
 *
 * @since V5
 */
@Getter
@Setter
@TableName("notification")
public class NotificationInbox {

    /** 雪花 ID。 */
    @TableId
    private Long id;

    /** 事件 ID，唯一键保证消费幂等。 */
    private String eventId;

    /** 接收人账号 ID。 */
    private Long userId;

    /** 业务类型。 */
    private String bizType;

    /** 业务单号。 */
    private String bizNo;

    /** 标题。 */
    private String title;

    /** 正文。 */
    private String content;

    /** 是否已读：0 未读 / 1 已读。 */
    private Integer readFlag;

    /** 创建时间。 */
    private LocalDateTime createdAt;
}
