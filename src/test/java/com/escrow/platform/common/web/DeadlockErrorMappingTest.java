package com.escrow.platform.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 锁冲突与依赖故障的错误码边界（FR-ESCROW.HOLD.04 ②，docs/requirements.md:465）。
 *
 * <p>该条要求：乐观锁版本冲突或死锁检测回滚 → 409 {@code CONCURRENT_MODIFICATION}，
 * 客户端可自动重试 1 次；而连不上依赖仍应是 503 {@code DEPENDENCY_UNAVAILABLE}。
 * 两者都是 {@code DataAccessException} 的子类，靠 Spring「最具体的 handler 获胜」区分。</p>
 *
 * <p>用 standalone MockMvc 而不拉整个容器：要验证的正是「哪个 handler 先接住」，
 * 与数据库、Redis、Flyway 都无关。</p>
 *
 * @since V6
 */
class DeadlockErrorMappingTest {

    /** 只为触发异常而存在的桩控制器。 */
    @RestController
    static class BoomController {

        @GetMapping("/boom/deadlock")
        public Object deadlock() {
            throw new DeadlockLoserDataAccessException("Deadlock found when trying to get lock", null);
        }

        @GetMapping("/boom/cannot-acquire")
        public Object cannotAcquire() {
            throw new CannotAcquireLockException("Lock wait timeout exceeded");
        }

        @GetMapping("/boom/connection")
        public Object connection() {
            throw new DataAccessResourceFailureException("Could not get JDBC Connection");
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new BoomController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void deadlockShouldMapTo409ConcurrentModification() throws Exception {
        mvc().perform(get("/boom/deadlock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void lockWaitTimeoutShouldMapTo409ConcurrentModification() throws Exception {
        mvc().perform(get("/boom/cannot-acquire"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void connectionFailureShouldStay503DependencyUnavailable() throws Exception {
        mvc().perform(get("/boom/connection"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }
}
