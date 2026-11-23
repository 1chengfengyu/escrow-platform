package com.escrow.platform.settlement.infrastructure.channel;

import com.escrow.platform.settlement.domain.ChannelTxn;

import java.time.LocalDate;
import java.util.List;

/**
 * 通道日终流水来源（V6，FR-RECON.JOB.01）。
 *
 * <p>对账只依赖本接口，不直接读通道实现：接真实通道时替换实现即可，
 * 差异识别与处置不用改。实现方不得在事务内调用远程接口。</p>
 *
 * @since V6
 */
public interface ChannelStatementSource {

    /**
     * 拉取指定日期的通道流水。
     *
     * @param date 对账日期
     * @return 通道流水；无流水返回空列表，不返回 {@code null}
     */
    List<ChannelTxn> statementOf(LocalDate date);
}
