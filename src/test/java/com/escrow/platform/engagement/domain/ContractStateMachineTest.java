package com.escrow.platform.engagement.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 签约状态机测试（v3-c3 / v3-c4，FR-SIGN.START.01 / FR-SIGN.CANCEL.01~.03 / FR-DELIVER.SUBMIT.02）。
 *
 * <p>「生效中」出发两条跃迁（开始 / 取消），「履约中」只允许交付；已交付是终态，不可迁出。</p>
 *
 * @since V3
 */
class ContractStateMachineTest {

    @Test
    void activeShouldBecomeStartedOnStart() {
        assertThat(ContractStateMachine.transit(ContractStatus.CONTRACT_ACTIVE,
                ContractStateMachine.Event.START))
                .isEqualTo(ContractStatus.CONTRACT_STARTED);
    }

    @Test
    void activeShouldBecomeCancelledOnCancel() {
        assertThat(ContractStateMachine.transit(ContractStatus.CONTRACT_ACTIVE,
                ContractStateMachine.Event.CANCEL))
                .isEqualTo(ContractStatus.CONTRACT_CANCELLED);
    }

    /** FR-DELIVER.SUBMIT.02：履约中 → 已交付。 */
    @Test
    void startedShouldDeliver() {
        assertThat(ContractStateMachine.transit(ContractStatus.CONTRACT_STARTED,
                ContractStateMachine.Event.DELIVER))
                .isEqualTo(ContractStatus.CONTRACT_DELIVERED);
    }

    /** FR-DELIVER.SUBMIT.02：未开始履约不能交付（没有履约过程）。 */
    @Test
    void activeShouldNotDeliver() {
        assertThatThrownBy(() -> ContractStateMachine.transit(ContractStatus.CONTRACT_ACTIVE,
                ContractStateMachine.Event.DELIVER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DELIVER");
    }

    /** FR-SIGN.CANCEL.03：履约中不得单方取消；也不能重复 START。 */
    @Test
    void startedShouldRejectStartAndCancel() {
        for (ContractStateMachine.Event event : new ContractStateMachine.Event[]{
                ContractStateMachine.Event.START, ContractStateMachine.Event.CANCEL}) {
            assertThatThrownBy(() -> ContractStateMachine.transit(ContractStatus.CONTRACT_STARTED, event))
                    .as("CONTRACT_STARTED 不能执行 %s", event)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** FR-ACCEPT.RESULT.01：已交付 → 已完成（验收通过，v4-c1）。 */
    @Test
    void deliveredShouldCompleteOnAccept() {
        assertThat(ContractStateMachine.transit(ContractStatus.CONTRACT_DELIVERED,
                ContractStateMachine.Event.ACCEPT))
                .isEqualTo(ContractStatus.CONTRACT_COMPLETED);
    }

    /** 已交付只允许验收；重复交付、开始、取消都必须被拒绝。 */
    @Test
    void deliveredShouldRejectOtherEvents() {
        for (ContractStateMachine.Event event : new ContractStateMachine.Event[]{
                ContractStateMachine.Event.START, ContractStateMachine.Event.CANCEL,
                ContractStateMachine.Event.DELIVER}) {
            assertThatThrownBy(() -> ContractStateMachine.transit(
                    ContractStatus.CONTRACT_DELIVERED, event))
                    .as("CONTRACT_DELIVERED 不能执行 %s", event)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void terminalStatusesShouldRejectBothEvents() {
        for (ContractStatus from : new ContractStatus[]{
                ContractStatus.CONTRACT_COMPLETED, ContractStatus.CONTRACT_CANCELLED}) {
            for (ContractStateMachine.Event event : ContractStateMachine.Event.values()) {
                assertThatThrownBy(() -> ContractStateMachine.transit(from, event))
                        .as("%s 不能执行 %s", from, event)
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void nullArgumentsShouldThrow() {
        assertThatThrownBy(() -> ContractStateMachine.transit(null, ContractStateMachine.Event.START))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ContractStateMachine.transit(ContractStatus.CONTRACT_ACTIVE, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
