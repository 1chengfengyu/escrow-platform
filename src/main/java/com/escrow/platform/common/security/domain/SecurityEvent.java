package com.escrow.platform.common.security.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 安全事件，对应表 {@code security_event}（V16，NFR-SEC.08）。
 *
 * <p>只追加不修改：本实体没有更新 / 删除入口，Service 也不暴露对应方法。
 * {@code reason} 与 {@code path} 在写入前截断，避免超长请求路径把插入打断。</p>
 *
 * @since V7
 */
@Getter
@Setter
@TableName("security_event")
public class SecurityEvent {

    /** 雪花 ID。 */
    @TableId
    private Long id;

    /** 触发账号；未登录为空。 */
    private Long userId;

    /** 触发时的默认角色。 */
    private String role;

    /** HTTP 方法；非 HTTP 场景写 UNKNOWN。 */
    private String method;

    /** 请求路径或操作标识。 */
    private String path;

    /** 拒绝原因。 */
    private String reason;

    /** 目标资源类型。 */
    private String targetType;

    /** 目标资源 ID。 */
    private Long targetId;

    /** 来源 IP。 */
    private String ip;

    /** 发生时间。 */
    private LocalDateTime createdAt;
}
