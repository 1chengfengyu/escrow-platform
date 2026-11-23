package com.escrow.platform.settlement.infrastructure.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * MongoDB 归档出口测试（V6，FR-RECON.ARCHIVE.01）：按文档整体覆盖写入。
 *
 * @since V6
 */
@ExtendWith(MockitoExtension.class)
class MongoReconArchiveStoreTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private MongoReconArchiveStore store;

    /** 归档调用 {@code save}，同一天的文档由 ID 覆盖。 */
    @Test
    void archiveShouldSaveDocument() {
        ReconArchiveDocument document = new ReconArchiveDocument("2026-11-18", "2026-11-18",
                11L, 3, 2, 1, 100L, true,
                List.of(new ReconArchiveDocument.StatementSnapshot("PO-1", 970L, true)),
                List.of(new ReconArchiveDocument.DifferenceSnapshot("PO-2", "CHANNEL_MISSING",
                        970L, null)),
                LocalDateTime.now());

        store.archive(document);
        store.archive(document);

        verify(mongoTemplate, times(2)).save(any(ReconArchiveDocument.class));
    }
}
