package com.escrow.platform.common.chat.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 消息类型（V11，补遗 §2.3，表 {@code chat_message.msg_type}）。
 *
 * <p>本期只做文字与系统提示，不做图片 / 附件，避免引出文件存储与审核链路。</p>
 *
 * <p><b>枚举名必须与 V23 的 {@code ck_chat_msg_type} 逐字一致</b>，改名即迁移失效。</p>
 *
 * @since V11
 */
public enum ChatMessageType {

    /** 用户文字消息。 */
    TEXT,

    /** 系统提示，{@code sender_id = 0}。 */
    SYSTEM;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
