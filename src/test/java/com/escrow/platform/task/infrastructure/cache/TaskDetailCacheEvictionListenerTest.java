package com.escrow.platform.task.infrastructure.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 缓存失效广播订阅端测试（V9）：收到广播只清本实例本地层。
 *
 * @since V9
 */
class TaskDetailCacheEvictionListenerTest {

    private InMemoryTaskDetailCacheStore local;
    private TaskDetailCacheEvictionListener listener;

    @BeforeEach
    void setUp() {
        local = new InMemoryTaskDetailCacheStore();
        listener = new TaskDetailCacheEvictionListener(local);
    }

    /** 收到其他实例的失效广播后，本地层必须清掉该任务。 */
    @Test
    void broadcastShouldInvalidateLocalEntry() {
        local.put(1L, snapshot(), 60L);

        listener.onMessage(message("1"), null);

        assertThat(local.contains(1L)).isFalse();
    }

    /** 非法载荷只记 warn，不能把监听线程打挂。 */
    @Test
    void invalidPayloadShouldBeIgnored() {
        assertThatCode(() -> listener.onMessage(message("not-a-number"), null))
                .doesNotThrowAnyException();
    }

    private static DefaultMessage message(String body) {
        return new DefaultMessage(
                TaskDetailCacheEvictionBroadcaster.CHANNEL.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static TaskDetailSnapshot snapshot() {
        LocalDateTime base = LocalDateTime.of(2026, 12, 1, 9, 0);
        return new TaskDetailSnapshot(
                1L, 1001L, "t", "desc", "地推", 5000L, 2,
                base, base.plusDays(1), "北京", "验收后结算",
                base.minusDays(1), null, 10_000L, 2, 0, List.of());
    }
}
