package com.escrow.platform.common.chat.domain;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话状态机测试（V11，补遗 §2.2，FR-MSG.CHAT.01）。
 *
 * <p>断言落到错误码而非仅异常类型：{@code INVALID_STATE_TRANSITION} 是协议码，
 * 只有它才能让上层返回 409 而不是 500。</p>
 *
 * @since V11
 */
class ChatConversationStateMachineTest {

    /** OPEN 可归档。 */
    @Test
    void openArchiveShouldGoArchived() {
        assertThat(ChatConversationStateMachine.transit(ChatConversationStatus.OPEN,
                ChatConversationStateMachine.Event.ARCHIVE))
                .isEqualTo(ChatConversationStatus.ARCHIVED);
    }

    /** ARCHIVED 是终态：重复归档抛 INVALID_STATE_TRANSITION。 */
    @Test
    void archivedArchiveShouldThrowInvalidStateTransition() {
        assertThatThrownBy(() -> ChatConversationStateMachine.transit(ChatConversationStatus.ARCHIVED,
                ChatConversationStateMachine.Event.ARCHIVE))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    /** 当前状态为空直接拒绝，不进入 switch。 */
    @Test
    void nullFromShouldThrowInvalidStateTransition() {
        assertThatThrownBy(() -> ChatConversationStateMachine.transit(null,
                ChatConversationStateMachine.Event.ARCHIVE))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    /** 未知（空）事件直接拒绝。 */
    @Test
    void nullEventShouldThrowInvalidStateTransition() {
        assertThatThrownBy(() -> ChatConversationStateMachine.transit(ChatConversationStatus.OPEN, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    /** 会话状态落库值必须与 V23 的 ck_chat_conv_status 逐字一致。 */
    @Test
    void statusCodesShouldMatchDatabaseCheck() {
        assertThat(ChatConversationStatus.OPEN.getCode()).isEqualTo("OPEN");
        assertThat(ChatConversationStatus.ARCHIVED.getCode()).isEqualTo("ARCHIVED");
    }

    /** 范围与消息类型的落库值必须与 V23 的 CHECK 逐字一致。 */
    @Test
    void scopeAndMessageTypeCodesShouldMatchDatabaseCheck() {
        assertThat(ChatScope.INQUIRY.getCode()).isEqualTo("INQUIRY");
        assertThat(ChatScope.APPLICATION.getCode()).isEqualTo("APPLICATION");
        assertThat(ChatScope.CONTRACT.getCode()).isEqualTo("CONTRACT");
        assertThat(ChatMessageType.TEXT.getCode()).isEqualTo("TEXT");
        assertThat(ChatMessageType.SYSTEM.getCode()).isEqualTo("SYSTEM");
    }
}
