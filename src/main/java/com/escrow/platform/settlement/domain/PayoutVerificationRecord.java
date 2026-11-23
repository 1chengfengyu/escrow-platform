package com.escrow.platform.settlement.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 打款查证流水，对应表 {@code payout_verification}（V12）。
 *
 * <p>只追加：每次查证一行，同一打款单可以有多行（上次 ERROR、这次 NOT_FOUND）。
 * 「当前查证结论」不冗余到此表之外，读的时候取最新一行即可。</p>
 *
 * <p>不加 {@code @Data} / {@code @Builder}（docs/tech-stack.md）。</p>
 *
 * @since V5
 */
@Getter
@Setter
@TableName("payout_verification")
public class PayoutVerificationRecord {

    /** 雪花 ID。 */
    @TableId
    private Long id;

    /** 打款单 ID。 */
    private Long payoutId;

    /** 被查证的请求号。 */
    private String requestNo;

    /** 查证结论。 */
    private ChannelVerification result;

    /** 通道侧实际金额（分），查不到为空。 */
    private Long actualAmount;

    /** 查证时间。 */
    private LocalDateTime createdAt;
}
