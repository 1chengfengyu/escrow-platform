package com.escrow.platform.ai.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.ai.infrastructure.entity.AiIndexStaleRefEntity;
import com.escrow.platform.ai.infrastructure.entity.AiIndexVersionEntity;
import com.escrow.platform.ai.infrastructure.mapper.AiIndexStaleRefMapper;
import com.escrow.platform.ai.infrastructure.mapper.AiIndexVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识索引版本与旧引用失效（V10-c3）。
 *
 * <p>只做两件事：切换 ACTIVE 版本；把旧版本里已下线的引用登记为失效。
 * 检索命中失效引用时直接丢弃，避免答案引用一条已经改过口径的条款。</p>
 *
 * @since V10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiIndexService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_RETIRED = "RETIRED";
    private static final String STATUS_BUILDING = "BUILDING";

    private final AiIndexVersionMapper versionMapper;
    private final AiIndexStaleRefMapper staleRefMapper;

    /**
     * 切换索引版本：旧 ACTIVE 转 RETIRED，目标版本置为 ACTIVE。
     *
     * @param versionNo  目标版本号
     * @param chunkCount 该版本收录条目数
     */
    public void activate(int versionNo, int chunkCount) {
        versionMapper.update(null, new LambdaUpdateWrapper<AiIndexVersionEntity>()
                .eq(AiIndexVersionEntity::getStatus, STATUS_ACTIVE)
                .set(AiIndexVersionEntity::getStatus, STATUS_RETIRED));

        AiIndexVersionEntity existing = findVersion(versionNo);
        if (existing == null) {
            AiIndexVersionEntity entity = new AiIndexVersionEntity();
            entity.setVersionNo(versionNo);
            entity.setStatus(STATUS_ACTIVE);
            entity.setChunkCount(chunkCount);
            entity.setActivatedAt(LocalDateTime.now());
            entity.setCreatedAt(LocalDateTime.now());
            versionMapper.insert(entity);
            return;
        }
        versionMapper.update(null, new LambdaUpdateWrapper<AiIndexVersionEntity>()
                .eq(AiIndexVersionEntity::getId, existing.getId())
                .set(AiIndexVersionEntity::getStatus, STATUS_ACTIVE)
                .set(AiIndexVersionEntity::getChunkCount, chunkCount)
                .set(AiIndexVersionEntity::getActivatedAt, LocalDateTime.now()));
    }

    /** 登记新建（BUILDING）版本；已存在同号版本直接返回，不重复插入。 */
    public void registerBuilding(int versionNo, int chunkCount) {
        if (findVersion(versionNo) != null) {
            return;
        }
        AiIndexVersionEntity entity = new AiIndexVersionEntity();
        entity.setVersionNo(versionNo);
        entity.setStatus(STATUS_BUILDING);
        entity.setChunkCount(chunkCount);
        entity.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(entity);
    }

    /**
     * 登记旧引用失效。
     *
     * @return 本次真正新增的条数（撞唯一键的不计）
     */
    public int invalidate(int versionNo, List<String> citationIds, String reason) {
        if (citationIds == null || citationIds.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (String citationId : citationIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList()) {
            AiIndexStaleRefEntity entity = new AiIndexStaleRefEntity();
            entity.setVersionNo(versionNo);
            entity.setCitationId(citationId);
            entity.setReason(reason);
            entity.setCreatedAt(LocalDateTime.now());
            try {
                staleRefMapper.insert(entity);
                inserted++;
            } catch (DuplicateKeyException ex) {
                // 已登记过：幂等，不算失败
                log.debug("旧引用失效重复登记 version={} citation={}", versionNo, citationId);
            }
        }
        return inserted;
    }

    /** 某引用是否已失效。 */
    public boolean isStale(String citationId) {
        if (citationId == null || citationId.isBlank()) {
            return false;
        }
        Long count = staleRefMapper.selectCount(new LambdaQueryWrapper<AiIndexStaleRefEntity>()
                .eq(AiIndexStaleRefEntity::getCitationId, citationId));
        return count != null && count > 0;
    }

    private AiIndexVersionEntity findVersion(int versionNo) {
        return versionMapper.selectOne(new LambdaQueryWrapper<AiIndexVersionEntity>()
                .eq(AiIndexVersionEntity::getVersionNo, versionNo));
    }
}
