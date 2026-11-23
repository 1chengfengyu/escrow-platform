package com.escrow.platform.identity.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 平台账号实体，对应表 {@code platform_user}
 * （由 V0 的 {@code V1__baseline.sql} 建好，字段清单见 docs/decisions.md #19）。
 *
 * <p>9 个字段一个不多一个不少，与 DDL 逐列对应。字段名与列名靠 MyBatis-Plus 的
 * 驼峰映射（{@code map-underscore-to-camel-case}）自动对齐，所以不写 {@code @TableField}。</p>
 *
 * <p>刻意不加 Lombok 的 {@code @Data}：</p>
 * <ul>
 *   <li>{@code @Data} 生成的 {@code equals/hashCode} 会破坏 MyBatis 一级缓存的实体身份语义；</li>
 *   <li>它的 {@code toString} 会把 {@link #passwordHash} 打进日志。</li>
 * </ul>
 * <p>也不加 {@code @Builder}：MyBatis-Plus 依赖无参构造，加了会报 {@code NoSuchMethodException}
 * （docs/decisions.md #39、docs/tech-stack.md）。</p>
 *
 * <p>{@code role} 只表示「注册时选择的默认身份」；业务授权以
 * {@link PlatformUserIdentity} 为准（docs/decisions.md #19 文末「V1 修订」）。</p>
 *
 * @since V1
 */
@Getter
@Setter
@TableName("platform_user")
public class PlatformUser {

    /**
     * 雪花 ID。全局 {@code id-type: assign_id}（docs/decisions.md #14），
     * 因此这里不写 {@code type = IdType.ASSIGN_ID}——重复声明只会让两处配置有机会不一致。
     */
    @TableId
    private Long id;

    /** 手机号，可空；手机号与邮箱至少有一个（DDL 的 ck_phone_or_email）。 */
    private String phone;

    /** 邮箱，可空。 */
    private String email;

    /** 昵称，用于管理端查询。 */
    private String nickname;

    /**
     * 口令哈希（BCrypt 60 字符，列宽 100 留换算法余量）。
     * 禁止出现在任何日志与响应体里：类上没有 {@code @Data}，也就没有自动 toString。
     */
    private String passwordHash;

    /** 注册时选择的默认身份，见 {@link UserRole}。 */
    private UserRole role;

    /** 账号状态，见 {@link UserStatus}。 */
    private UserStatus status;

    /** 创建时间，由数据库默认值写入（DATETIME(3)，东八区，docs/decisions.md #17）。 */
    private LocalDateTime createdAt;

    /** 更新时间，由数据库 ON UPDATE 维护。 */
    private LocalDateTime updatedAt;
}
