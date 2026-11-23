package com.escrow.platform.common.chat.domain;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;

/**
 * 会话状态机（V11，补遗 §2.2）。
 *
 * <p>唯一跃迁：{@link Event#ARCHIVE} 把 {@link ChatConversationStatus#OPEN} 置为
 * {@link ChatConversationStatus#ARCHIVED}。归档由任务终态事件触发，不提供用户手动关闭，
 * 也不提供回退到 {@code OPEN} 的事件——归档后消息永久可查，只读。</p>
 *
 * <p>非法跃迁抛 {@link ApiException} + {@link ErrorCode#INVALID_STATE_TRANSITION}（409），
 * 与资金状态机的协议码保持一致；本类位于 {@code domain} 层，不依赖 Spring / MyBatis / Jackson。</p>
 *
 * @since V11
 */
public final class ChatConversationStateMachine {

    /** 会话状态跃迁事件。 */
    public enum Event {

        /** 任务进入终态，会话归档（FR-MSG.CHAT.01，补遗 §2.2）。 */
        ARCHIVE
    }

    private ChatConversationStateMachine() {
    }

    /**
     * 计算跃迁后的状态。
     *
     * @param from  当前状态
     * @param event 事件
     * @return 跃迁后的状态
     * @throws ApiException 参数为空或跃迁非法，错误码 {@code INVALID_STATE_TRANSITION}
     */
    public static ChatConversationStatus transit(ChatConversationStatus from, Event event) {
        if (from == null || event == null) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "会话状态跃迁的参数不能为空");
        }
        return switch (event) {
            case ARCHIVE -> {
                if (from != ChatConversationStatus.OPEN) {
                    throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                            "会话 " + from + " 不能执行 ARCHIVE");
                }
                yield ChatConversationStatus.ARCHIVED;
            }
        };
    }
}
