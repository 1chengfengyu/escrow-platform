package com.escrow.platform.settlement.infrastructure.archive;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 日志模式归档（V6，默认实现）。
 *
 * <p>没有 MongoDB 时也能完整跑通对账；只把汇总与差异条数写日志，
 * 明细报文需要时把 {@code ESCROW_RECON_ARCHIVE_MODE} 切成 {@code mongo}。</p>
 *
 * @since V6
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "escrow.recon.archive-mode", havingValue = "log", matchIfMissing = true)
public class LogReconArchiveStore implements ReconArchiveStore {

    @Override
    public void archive(ReconArchiveDocument document) {
        log.info("对账报文归档(日志模式) reconDate={} batchId={} local={} channel={} diff={} "
                        + "diffAmount={} identityOk={}",
                document.reconDate(), document.batchId(), document.localCount(),
                document.channelCount(), document.diffCount(), document.diffAmount(),
                document.identityOk());
    }
}
