package com.escrow.platform.common.chat.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.common.chat.domain.ChatConversation;
import com.escrow.platform.common.chat.domain.ChatScope;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code chat_conversation} 的持久化入口（V11，CARD-03）。
 *
 * <p>唯一键回查、按身份分页、按任务批量归档走 XML：这些 SQL 依赖唯一索引与
 * {@code LIMIT/OFFSET} 语义，写在 XML 里便于真库验证（docs/verification.md 第 3 节）。</p>
 *
 * <p>接口上不加 {@code @Mapper}：由 {@code MyBatisMapperScanConfig} 统一扫描。</p>
 *
 * @since V11
 */
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {

    /**
     * 按唯一键 {@code (scope, task_id, biz_id, employer_id, worker_id)} 回查既有会话。
     *
     * <p>插入撞唯一键后调用本方法取回已存在记录，禁止「先 SELECT 判断再 INSERT」。</p>
     *
     * @return 命中的会话；不存在返回 {@code null}
     */
    ChatConversation selectByUniqueKey(@Param("scope") ChatScope scope,
                                       @Param("taskId") Long taskId,
                                       @Param("bizId") Long bizId,
                                       @Param("employerId") Long employerId,
                                       @Param("workerId") Long workerId);

    /**
     * 按当前身份分页查询会话，按最后消息时间倒序。
     *
     * @param asEmployer {@code true} 只查 {@code employer_id}，{@code false} 只查 {@code worker_id}
     * @param scope      可选范围过滤，{@code null} 表示全部
     * @param offset     偏移量
     * @param limit      页大小，由服务层夹紧
     */
    List<ChatConversation> selectPageByIdentity(@Param("userId") Long userId,
                                                @Param("asEmployer") boolean asEmployer,
                                                @Param("scope") ChatScope scope,
                                                @Param("offset") int offset,
                                                @Param("limit") int limit);

    /** 与 {@link #selectPageByIdentity} 同条件的总数。 */
    long countByIdentity(@Param("userId") Long userId,
                         @Param("asEmployer") boolean asEmployer,
                         @Param("scope") ChatScope scope);

    /** 任务终态归档：只把该任务下的 {@code OPEN} 置为 {@code ARCHIVED}，幂等。 */
    int archiveByTaskId(@Param("taskId") Long taskId);

    /** 追加消息后刷新最后消息时间（只改这一列，不用 {@code updateById}）。 */
    int updateLastMessageAt(@Param("id") Long id,
                            @Param("lastMessageAt") LocalDateTime lastMessageAt);
}
