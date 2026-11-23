package com.escrow.platform.common.chat.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 会话状态（V11，补遗 §2.2，表 {@code chat_conversation.status}）。
 *
 * <p>{@link #OPEN} 可发消息；{@link #ARCHIVED} 由任务终态事件自动归档，只读且历史永久可查。
 * 不提供用户手动关闭或删除。</p>
 *
 * <p><b>枚举名必须与 V23 的 {@code ck_chat_conv_status} 逐字一致</b>，改名即迁移失效。</p>
 *
 * @since V11
 */
public enum ChatConversationStatus {

    /** 进行中，可发消息。 */
    OPEN,

    /** 已归档：任务进入终态后自动置位，只读。 */
    ARCHIVED;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
