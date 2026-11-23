package com.escrow.platform.common.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.common.notification.NotificationInbox;

/**
 * {@code notification} 的持久化入口（V5）。
 *
 * <p>放在 {@code infrastructure.mapper} 下是为了被
 * {@code MyBatisMapperScanConfig} 的 {@code **.infrastructure.mapper} 扫到；
 * 实体仍留在 {@code common.notification}，Mapper 与实体不必同包。</p>
 *
 * @since V5
 */
public interface NotificationInboxMapper extends BaseMapper<NotificationInbox> {
}
