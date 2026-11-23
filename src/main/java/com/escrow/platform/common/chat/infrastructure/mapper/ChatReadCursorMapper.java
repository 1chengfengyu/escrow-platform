package com.escrow.platform.common.chat.infrastructure.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * {@code chat_read_cursor} 的持久化入口（V11，CARD-03）。
 *
 * <p><b>刻意不继承 {@code BaseMapper}</b>：本表是复合主键
 * {@code (conversation_id, user_id)}，MyBatis-Plus 只支持单主键，
 * {@code selectById/updateById} 在这里只会写错行。写入统一走
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} + {@code GREATEST}，天然幂等且不回退。</p>
 *
 * <p>接口上不加 {@code @Mapper}：由 {@code MyBatisMapperScanConfig} 统一扫描。</p>
 *
 * @since V11
 */
public interface ChatReadCursorMapper {

    /**
     * 推进读游标；已存在时只增不减（{@code GREATEST}）。
     *
     * @return 受影响行数（MySQL 对无变化的 UPDATE 返回 0，调用方不得据此判失败）
     */
    int upsert(@Param("conversationId") Long conversationId,
               @Param("userId") Long userId,
               @Param("lastReadMessageId") Long lastReadMessageId);

    /** 读取游标；从未标记已读返回 {@code null}。 */
    Long selectLastReadMessageId(@Param("conversationId") Long conversationId,
                                 @Param("userId") Long userId);
}
