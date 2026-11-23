package com.escrow.platform.identity.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 账号身份实体，对应表 {@code platform_user_identity}
 * （由 {@code V2__platform_user_identity.sql} 建好，见 docs/decisions.md #18 / #19）。
 *
 * <p>为什么要有这张表：SRS FR-ACCT.ROLE.01 要求「同一账号可同时具备雇主与劳动者身份」。
 * 一个账号可以有多行身份，{@code uk_user_identity (user_id, identity_type)} 保证
 * 同一身份不会重复开通。</p>
 *
 * <p>{@link #userId} 是逻辑外键：不建物理外键，跨表一致性由应用保证
 * （project_rules 域间规则①，V8 拆服务时这条线就是切分线）。</p>
 *
 * <p>不加 Lombok {@code @Data}：会破坏实体身份语义；也不加 {@code @Builder}：
 * MyBatis-Plus 需要无参构造（docs/decisions.md #39）。字段名与列名靠驼峰映射，不写 {@code @TableField}。</p>
 *
 * @since V1
 */
@Getter
@Setter
@TableName("platform_user_identity")
public class PlatformUserIdentity {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type（docs/decisions.md #14）。 */
    @TableId
    private Long id;

    /** 指向 {@code platform_user.id}，只存 ID 不 join 对方表（域间规则①）。 */
    private Long userId;

    /** 身份类型，见 {@link IdentityType}。 */
    private IdentityType identityType;

    /** 该身份的状态，见 {@link IdentityStatus}。 */
    private IdentityStatus status;

    /** 创建时间，数据库默认值写入。 */
    private LocalDateTime createdAt;

    /** 更新时间，数据库 ON UPDATE 维护。 */
    private LocalDateTime updatedAt;
}
