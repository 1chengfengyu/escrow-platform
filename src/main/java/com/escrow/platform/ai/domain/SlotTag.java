package com.escrow.platform.ai.domain;

import java.util.List;

/**
 * 槽位回显标签（V10-c2）：模型理解出的条件先给用户看、可改，用户确认后才检索。
 *
 * <p>这是"理解错误可被当场拦下"的实现载体：{@code key} 与 {@link QuerySlots} 字段一一对应，
 * 前端删掉某个标签就等价于把该槽位置空再检索。因此它必须可逆——{@link #toSlots()}
 * 能还原成同一组槽位。</p>
 *
 * @param key   槽位键：skill / priceMax / location / weekend / headcount
 * @param label 展示文案（中文）
 * @param value 展示值（金额已从分转成元，仅用于展示）
 * @since V10
 */
public record SlotTag(String key, String label, String value) {

    /** 由槽位生成可编辑标签；没有的槽位不生成标签。 */
    public static List<SlotTag> from(QuerySlots slots) {
        java.util.List<SlotTag> tags = new java.util.ArrayList<>();
        if (slots.skill() != null) {
            tags.add(new SlotTag("skill", "技能", slots.skill()));
        }
        if (slots.priceMax() != null) {
            tags.add(new SlotTag("priceMax", "预算上限", (slots.priceMax() / 100) + " 元"));
        }
        if (slots.location() != null) {
            tags.add(new SlotTag("location", "地点", slots.location()));
        }
        if (Boolean.TRUE.equals(slots.weekend())) {
            tags.add(new SlotTag("weekend", "档期", "周末可接单"));
        }
        if (slots.headcount() != null) {
            tags.add(new SlotTag("headcount", "人数", slots.headcount() + " 人"));
        }
        return List.copyOf(tags);
    }
}
