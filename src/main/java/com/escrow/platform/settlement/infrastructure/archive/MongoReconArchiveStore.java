package com.escrow.platform.settlement.infrastructure.archive;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * MongoDB 归档实现（V6，{@code escrow.recon.archive-mode=mongo} 时生效）。
 *
 * <p>{@code save} 按 {@code @Id} 覆盖写入，同一天重跑对账不会产生重复文档。</p>
 *
 * @since V6
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "escrow.recon.archive-mode", havingValue = "mongo")
public class MongoReconArchiveStore implements ReconArchiveStore {

    private final MongoTemplate mongoTemplate;

    @Override
    public void archive(ReconArchiveDocument document) {
        mongoTemplate.save(document);
        log.info("对账报文已归档到 MongoDB reconDate={} channel={} diff={}",
                document.reconDate(), document.channelCount(), document.diffCount());
    }
}
