package com.escrow.platform.engagement.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 报名单状态机测试（v3-c2 / v3-c3，SRS A.3.7）。
 *
 * <p>锁三条跃迁：待处理 → 已录用（HIRE）、待处理 → 已拒绝（REJECT）、
 * 已录用 → 已撤回（WITHDRAW，取消签约）；其余组合必须抛异常，防止服务层「顺手」把终态改回去。</p>
 *
 * @since V3
 */
class ApplicationStateMachineTest {

    @Test
    void pendingShouldBecomeHiredOnHire() {
        assertThat(ApplicationStateMachine.transit(
                ApplicationStatus.APPLY_PENDING, ApplicationStateMachine.Event.HIRE))
                .isEqualTo(ApplicationStatus.APPLY_HIRED);
    }

    @Test
    void pendingShouldBecomeRejectedOnReject() {
        assertThat(ApplicationStateMachine.transit(
                ApplicationStatus.APPLY_PENDING, ApplicationStateMachine.Event.REJECT))
                .isEqualTo(ApplicationStatus.APPLY_REJECTED);
    }

    @Test
    void hiredShouldBecomeWithdrawnOnWithdraw() {
        assertThat(ApplicationStateMachine.transit(
                ApplicationStatus.APPLY_HIRED, ApplicationStateMachine.Event.WITHDRAW))
                .isEqualTo(ApplicationStatus.APPLY_WITHDRAWN);
    }

    @Test
    void pendingShouldRejectWithdraw() {
        assertThatThrownBy(() -> ApplicationStateMachine.transit(
                ApplicationStatus.APPLY_PENDING, ApplicationStateMachine.Event.WITHDRAW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WITHDRAW");
    }

    @Test
    void hiredShouldRejectHireAndReject() {
        for (ApplicationStateMachine.Event event : new ApplicationStateMachine.Event[]{
                ApplicationStateMachine.Event.HIRE, ApplicationStateMachine.Event.REJECT}) {
            assertThatThrownBy(() -> ApplicationStateMachine.transit(ApplicationStatus.APPLY_HIRED, event))
                    .as("已录用 不能执行 %s", event)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void terminalStatusesShouldRejectAllEvents() {
        for (ApplicationStatus from : new ApplicationStatus[]{
                ApplicationStatus.APPLY_REJECTED,
                ApplicationStatus.APPLY_WITHDRAWN}) {
            for (ApplicationStateMachine.Event event : ApplicationStateMachine.Event.values()) {
                assertThatThrownBy(() -> ApplicationStateMachine.transit(from, event))
                        .as("%s 不能执行 %s", from, event)
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void nullArgumentsShouldThrow() {
        assertThatThrownBy(() -> ApplicationStateMachine.transit(null,
                ApplicationStateMachine.Event.HIRE))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ApplicationStateMachine.transit(
                ApplicationStatus.APPLY_PENDING, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
