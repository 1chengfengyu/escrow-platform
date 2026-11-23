package com.escrow.platform.settlement.infrastructure.channel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.settlement.domain.ChannelTxn;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 模拟通道流水来源：用本地打款尝试镜像出「通道认为的结果」。
 *
 * <p><b>为什么默认实现镜像本地</b>：模拟通道没有独立的流水库，
 * 只有出站记录。这样在正常数据上对账结果恒为「无差异」，
 * 差异场景由测试用 {@code @Primary} 的受控实现注入，避免把测试开关写进生产路径。</p>
 *
 * <p>映射口径：本地 {@code PAYOUT_SUCCESS} 视作通道成功（金额取实际出款），
 * 其余状态不出现在通道流水里——未知态因此会被识别为
 * {@code LOST_RESPONSE / RESULT_UNDETERMINED} 的候选。</p>
 *
 * @since V6
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMirrorChannelStatementSource implements ChannelStatementSource {

    private final PayoutOrderMapper payoutOrderMapper;

    @Override
    public List<ChannelTxn> statementOf(LocalDate date) {
        List<PayoutOrder> attempts = payoutOrderMapper.selectList(new LambdaQueryWrapper<PayoutOrder>()
                .ge(PayoutOrder::getCreatedAt, date.atStartOfDay())
                .lt(PayoutOrder::getCreatedAt, date.plusDays(1).atStartOfDay()));
        List<ChannelTxn> statement = new ArrayList<>(attempts.size());
        for (PayoutOrder attempt : attempts) {
            if (attempt.getStatus() == PayoutStatus.PAYOUT_SUCCESS) {
                long amount = attempt.getActualAmount() != null
                        ? attempt.getActualAmount() : attempt.getAmount();
                statement.add(new ChannelTxn(attempt.getRequestNo(), amount, true));
            }
        }
        log.debug("模拟通道流水 date={} records={}", date, statement.size());
        return statement;
    }
}
