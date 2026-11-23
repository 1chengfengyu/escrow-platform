package com.escrow.platform.common.chat.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.common.chat.domain.ChatMessage;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code chat_message} 的持久化入口（V11，CARD-03）。
 *
 * <p>只提供追加读取：正序分页、{@code afterId} 增量、未读聚合、最大消息 ID。
 * 没有编辑 / 撤回 / 删除语句——表结构与 SQL 两层同时锁死「只追加」。</p>
 *
 * <p>接口上不加 {@code @Mapper}：由 {@code MyBatisMapperScanConfig} 统一扫描。</p>
 *
 * @since V11
 */
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /** 按 {@code (created_at, id)} 升序分页，{@code limit} 由服务层夹到 ≤50。 */
    List<ChatMessage> selectPageAsc(@Param("conversationId") Long conversationId,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    /** 轮询增量：只取 ID 大于 {@code afterId} 的消息，按 {@code (created_at, id)} 升序。 */
    List<ChatMessage> selectAfterId(@Param("conversationId") Long conversationId,
                                    @Param("afterId") long afterId,
                                    @Param("limit") int limit);

    /** 单个会话的未读数：{@code id > 读游标} 且不是自己发的。 */
    long countUnread(@Param("conversationId") Long conversationId,
                     @Param("userId") Long userId);

    /**
     * 当前身份下全部会话的未读总数。
     *
     * @param asEmployer {@code true} 统计 {@code employer_id = userId} 的会话，
     *                   {@code false} 统计 {@code worker_id = userId} 的会话
     */
    long countUnreadTotal(@Param("userId") Long userId,
                          @Param("asEmployer") boolean asEmployer);

    /** 会话内最大消息 ID；无消息返回 {@code null}。 */
    Long selectMaxId(@Param("conversationId") Long conversationId);
}
