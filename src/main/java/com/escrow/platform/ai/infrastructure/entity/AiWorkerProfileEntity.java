package com.escrow.platform.ai.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 服务者画像持久化对象（V10-c3，表 {@code ai_worker_profile}）。
 *
 * <p>除 {@code userId} / {@code profileVersion} 外一律允许空：冷启动用户没有报价、
 * 没有城市、没有评分，仍然要能入库、能被查询，接口不能报错。</p>
 *
 * <p>{@code sourceFlags} 存 JSON 字符串，不走 TypeHandler：只在本域内读写，
 * 多一个 TypeHandler 就多一处 MyBatis 配置要跟着实体走，收益不抵成本。</p>
 *
 * @since V10
 */
@Getter
@Setter
@TableName("ai_worker_profile")
public class AiWorkerProfileEntity {

    @TableId
    private Long id;

    private Long userId;

    /** 技能标签，英文逗号分隔，已清洗。 */
    private String skills;

    private Integer experienceYears;

    /** 期望单价，单位分；未知为 {@code null}。 */
    private Long expectedUnitPrice;

    private String workLocation;

    private Boolean availableWeekend;

    private Integer completedOrders;

    /** 评分 ×100，0~500。 */
    private Integer ratingX100;

    /** 字段来源映射 JSON：字段名 → ACTUAL / DERIVED / USER_INPUT / MISSING / FIXTURE。 */
    private String sourceFlags;

    private Integer profileVersion;

    private LocalDateTime updatedAt;
}
