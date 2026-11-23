package com.escrow.platform.settlement.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 打款人工处置记录，对应表 {@code payout_manual_decision}（V12）。
 *
 * <p>只追加：同一打款单可能先「转差异」再「确认实际金额」，两次都要留痕，
 * 不允许覆盖上一条。</p>
 *
 * @since V5
 */
@Getter
@Setter
@TableName("payout_manual_decision")
public class PayoutManualDecision {

    /** 雪花 ID。 */
    @TableId
    private Long id;

    /** 打款单 ID。 */
    private Long payoutId;

    /** 处置动作。 */
    private PayoutManualDecisionType decision;

    /** 确认的实际金额（分）；转差异为空。 */
    private Long actualAmount;

    /** 处置人账号 ID。 */
    private Long operatorId;

    /** 处置说明 / 零扣款凭据编号。 */
    private String remark;

    /** 处置时间。 */
    private LocalDateTime createdAt;
}
