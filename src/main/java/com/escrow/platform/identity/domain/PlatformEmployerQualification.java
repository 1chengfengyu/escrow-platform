package com.escrow.platform.identity.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 雇主资质实体，对应表 {@code platform_employer_qualification}
 * （由 {@code V3__platform_employer_qualification.sql} 建好）。
 *
 * <p>与 {@code platform_user} 是 1:1：{@code uk_employer_qual_user} 保证同一用户只有一行，
 * 重复提交走 UPDATE 而不是 INSERT。</p>
 *
 * <p>不加 Lombok {@code @Data}：{@code equals/hashCode} 会破坏 MyBatis 一级缓存的实体身份语义，
 * 且 {@code toString} 会把证件后 4 位写进日志；也不加 {@code @Builder}：MyBatis-Plus 依赖无参构造
 * （docs/decisions.md #39、docs/tech-stack.md）。</p>
 *
 * <p>{@code reviewStatus} 不会是 {@link ReviewStatus#UNSUBMITTED}：未提交时库里没有这一行，
 * DDL 的 CHECK 也只允许 PENDING / APPROVED / REJECTED。</p>
 *
 * @since V1
 */
@Getter
@Setter
@TableName("platform_employer_qualification")
public class PlatformEmployerQualification {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type（docs/decisions.md #14）。 */
    @TableId
    private Long id;

    /** 指向 {@code platform_user.id}，逻辑外键，不建物理外键。 */
    private Long userId;

    /** 主体类型，见 {@link SubjectType}。 */
    private SubjectType subjectType;

    /** 主体名称，落库存 trim 后的值。 */
    private String subjectName;

    /** 证件号后 4 位，DDL 用 CHECK 限定长度为 4。 */
    private String idLast4;

    /** 审核状态，见 {@link ReviewStatus}；库里不会出现 UNSUBMITTED。 */
    private ReviewStatus reviewStatus;

    /** 审核人账号 ID，未审核为空。 */
    private Long reviewerId;

    /** 驳回理由 / 审核备注；重新提交时清空。 */
    private String reviewReason;

    /** 最近一次提交时间，重新提交时刷新。 */
    private LocalDateTime submittedAt;

    /** 最近一次审核时间，重新提交时清空。 */
    private LocalDateTime reviewedAt;

    /** 创建时间，数据库默认值写入。 */
    private LocalDateTime createdAt;

    /** 更新时间，数据库 ON UPDATE 维护。 */
    private LocalDateTime updatedAt;
}
