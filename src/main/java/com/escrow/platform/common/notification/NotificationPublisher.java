package com.escrow.platform.common.notification;

/**
 * 站内信事件发布器（V5）。
 *
 * <p>两种实现：{@code log} 模式只打日志（默认，无 broker 也能跑），
 * {@code kafka} 模式投递到 Kafka。发布失败不影响业务事务。</p>
 *
 * @since V5
 */
public interface NotificationPublisher {

    /**
     * 发布一条通知事件。
     *
     * @param message 通知事件
     */
    void publish(NotificationMessage message);
}
