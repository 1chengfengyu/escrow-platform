package com.escrow.platform.common.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.common.notification.NotificationDeadLetter;

/**
 * {@code notification_dead_letter} 的持久化入口（V5）。
 *
 * @since V5
 */
public interface NotificationDeadLetterMapper extends BaseMapper<NotificationDeadLetter> {
}
