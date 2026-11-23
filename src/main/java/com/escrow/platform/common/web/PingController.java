package com.escrow.platform.common.web;

import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * V0 验收入口（依据 docs/decisions.md #32）。
 *
 * <p>三条通路：{@code /api/ping} 200、{@code /api/ping/echo} 400、{@code /api/ping/boom} 500。
 * {@code echo} 用真实 {@code @NotBlank} 校验，而不是假抛异常——V1~V7 天天走的就是这条校验链路。</p>
 *
 * @since V0
 */
@RestController
@RequestMapping("/api/ping")
public class PingController {

    /** 200 通路：{@code {"code":"SUCCESS","message":"ok","data":{"pong":true},"traceId":"..."}}。 */
    @GetMapping
    public ApiResponse<Map<String, Boolean>> ping() {
        return ApiResponse.ok(Map.of("pong", true));
    }

    /** 400 通路：{@code name} 空串 / 空白时由参数校验拦截。 */
    @GetMapping("/echo")
    public ApiResponse<Map<String, String>> echo(
            @RequestParam @NotBlank(message = "不能为空") String name) {
        return ApiResponse.ok(Map.of("name", name));
    }
}

/**
 * 500 通路：故意抛异常，验证全局异常处理器。
 *
 * <p>{@code @Profile} 是 bean 级条件注解，挂在 {@code @GetMapping} 方法上不生效，
 * 所以单独成类并由 {@code dev} profile 限定——绝不把「必抛异常」的接口留在生产。</p>
 *
 * @since V0
 */
@RestController
@RequestMapping("/api/ping")
@Profile("dev")
class PingBoomController {

    @GetMapping("/boom")
    public ApiResponse<Void> boom() {
        throw new IllegalStateException("V0 故意抛出的异常，用于验证 GlobalExceptionHandler");
    }
}
