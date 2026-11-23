package com.escrow.platform.common.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/ping} 三条通路（依据 docs/decisions.md #32 / #33）。
 *
 * <p>standalone MockMvc：不启 Spring 容器、不连 MySQL / Redis，保证
 * 「MySQL 未启动时 {@code mvn test} 必须是绿的」。</p>
 *
 * @since V0
 */
class PingControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PingController(), new PingBoomController())
                .addFilters(new TraceIdFilter())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** #32 通路一：200 + SUCCESS + pong=true + traceId 非空。 */
    @Test
    void pingShouldReturnSuccess() throws Exception {
        mockMvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(header().exists(TraceIdFilter.TRACE_ID_HEADER))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.pong").value(true))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    /** #32 通路二：400 + VALIDATION_FAILED +「name 不能为空」+ traceId 非空。 */
    @Test
    void echoWithBlankNameShouldReturnValidationFailed() throws Exception {
        mockMvc.perform(get("/api/ping/echo").param("name", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("name 不能为空"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    /** 缺参（不是空串）：必须 400，不能兜成 500（#31 参数错 400）。 */
    @Test
    void echoWithoutNameShouldReturnValidationFailed() throws Exception {
        mockMvc.perform(get("/api/ping/echo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** #32 通路三：500 + INTERNAL_ERROR + 固定文案，响应体不得含堆栈。 */
    @Test
    void boomShouldReturnInternalError() throws Exception {
        mockMvc.perform(get("/api/ping/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("com.escrow.platform"))));
    }
}
