package com.escrow.platform.fund.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 资金账户，对应表 {@code fund_account}（由 {@code V4__v2_core_tables.sql} 建好）。
 *
 * <p>同一用户同一科目只有一行、系统科目全局一行，由两个生成列唯一索引保证；
 * {@code ACC_ISSUED} 不得按用户拆分（SRS 3.4.1 恒等式）。</p>
 *
 * <p>不加 {@code @Data}：{@code equals/hashCode} 会破坏 MyBatis 一级缓存的实体身份语义，
 * 且 {@code toString} 会把余额哈希写进日志；也不加 {@code @Builder}：MyBatis-Plus 依赖无参构造
 * （docs/decisions.md #39、docs/tech-stack.md）。</p>
 *
 * <p>乐观锁拦截器在 v2-c5 接，本卡只落列与属性，不加 {@code @Version}。</p>
 *
 * <p>状态机见 SRS 4.2.2「资金账户」。</p>
 *
 * @since V2
 */
@Getter
@Setter
@TableName("fund_account")
public class FundAccount {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type（docs/decisions.md #14）。 */
    @TableId
    private Long id;

    /** 归属类型：USER（个人）/ SYSTEM（平台）。 */
    private AccountOwnerType ownerType;

    /** USER 归属的用户 ID，逻辑外键 platform_user.id；SYSTEM 归属为 null。 */
    private Long userId;

    /** 科目，六科目之一；USER 只能是 ACC_AVAIL / ACC_FROZEN，SYSTEM 只能是后四个（ck_fund_account_subject）。 */
    private AccountSubject subject;

    /** 余额（分），库层 ck_fund_account_balance 兜住 >= 0。 */
    private Long balance;

    /** 乐观锁版本号；本卡只读不写，拦截器与 {@code @Version} 注解在 v2-c5 接。 */
    private Integer version;

    /** 创建时间，数据库默认值写入。 */
    private LocalDateTime createdAt;

    /** 更新时间，数据库 ON UPDATE 维护。 */
    private LocalDateTime updatedAt;
}
