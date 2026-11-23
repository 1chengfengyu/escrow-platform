package com.escrow.platform.engagement.domain;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;

import java.util.Map;

/**
 * 「我的签约」分页参数（数据权限第一层）。
 *
 * <p>与 {@link ApplicationPageQuery} 同一手法：Controller 用 {@code String} 收参后交给
 * {@link #from}，非法值抛 {@code INVALID_PARAMETER}，不落到全局 500。</p>
 *
 * @param page 页码，从 1 开始
 * @param size 每页条数，1~100
 * @since V3
 */
public record ContractPageQuery(int page, int size) {

    private static final String INVALID_MESSAGE = "请求参数不合法";
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    /**
     * 解析并校验分页参数。
     *
     * @throws ApiException 任一参数不合法，{@code code=INVALID_PARAMETER}
     */
    public static ContractPageQuery from(String page, String size) {
        return new ContractPageQuery(parsePage(page), parseSize(size));
    }

    private static int parsePage(String value) {
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_PAGE;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw invalid("page", "必须是整数");
        }
        if (parsed < 1) {
            throw invalid("page", "不能小于 1");
        }
        return parsed;
    }

    private static int parseSize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_SIZE;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw invalid("size", "必须是整数");
        }
        if (parsed < 1 || parsed > MAX_SIZE) {
            throw invalid("size", "必须在 1~" + MAX_SIZE + " 之间");
        }
        return parsed;
    }

    private static ApiException invalid(String field, String reason) {
        return new ApiException(ErrorCode.INVALID_PARAMETER, INVALID_MESSAGE,
                Map.of("fields", Map.of(field, reason)));
    }
}
