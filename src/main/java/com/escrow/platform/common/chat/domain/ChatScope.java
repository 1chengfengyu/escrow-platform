package com.escrow.platform.common.chat.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 会话范围（V11，补遗 §2.1，表 {@code chat_conversation.scope}）。
 *
 * <p>三类会话共用一张表，用 {@code scope + biz_id} 区分：</p>
 * <ul>
 *   <li>{@link #INQUIRY} 咨询：任务招募中即可开，{@code biz_id} 固定 {@code 0}；</li>
 *   <li>{@link #APPLICATION} 报名：劳动者提交报名后自动开，{@code biz_id} = 报名 ID；</li>
 *   <li>{@link #CONTRACT} 签约：录用 / 签约生效后自动开，{@code biz_id} = 签约 ID。</li>
 * </ul>
 *
 * <p><b>枚举名必须与 V23 的 {@code ck_chat_conv_scope} 逐字一致</b>，改名即迁移失效。</p>
 *
 * @since V11
 */
public enum ChatScope {

    /** 咨询：任务招募中即可开，不依赖报名或签约。 */
    INQUIRY,

    /** 报名沟通：一个报名一个会话。 */
    APPLICATION,

    /** 履约沟通：一个签约一个会话（1 任务 N 名额 → N 个会话）。 */
    CONTRACT;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
