package com.escrow.platform.engagement.domain;

/**
 * 雇主验收结论（FR-ACCEPT.MANUAL.01）。
 *
 * @since V4
 */
public enum AcceptanceDecision {

    /** 通过：触发结算与打款。 */
    PASS,

    /** 拒收：名额转待裁决并冻结资金。 */
    REJECT;

    /**
     * 解析请求体中的结论；只接受 {@code PASS / REJECT}（大小写不敏感）。
     *
     * @param raw 原始值
     * @return 结论
     * @throws IllegalArgumentException 取值非法
     */
    public static AcceptanceDecision parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("验收结论不能为空");
        }
        return switch (raw.trim().toUpperCase()) {
            case "PASS" -> PASS;
            case "REJECT" -> REJECT;
            default -> throw new IllegalArgumentException("验收结论只能是 PASS 或 REJECT");
        };
    }
}
