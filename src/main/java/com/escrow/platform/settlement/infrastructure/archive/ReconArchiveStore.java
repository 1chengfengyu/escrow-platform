package com.escrow.platform.settlement.infrastructure.archive;

/**
 * 对账报文归档出口（V6，FR-RECON.ARCHIVE.01）。
 *
 * <p>实现两种：{@code log}（默认，只写日志，无需 Mongo）与 {@code mongo}（落 MongoDB）。
 * 归档失败不得影响对账结果——调用方负责吞掉异常并告警。</p>
 *
 * @since V6
 */
public interface ReconArchiveStore {

    /**
     * 归档一天的报文与差异快照。
     *
     * @param document 归档文档，同一天重复归档按覆盖处理
     */
    void archive(ReconArchiveDocument document);
}
