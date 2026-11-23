package com.escrow.platform.ai.domain;

import java.util.List;

/**
 * 查询理解的产物：雇主口语需求被翻译成的结构化槽位（V10-c2）。
 *
 * <p><b>这里不存事实</b>：所有字段都来自用户这句话本身，模型只做"翻译"，
 * 不生成任何数据库里没有的东西。因此下游检索、理由、引用都建立在真实数据上。</p>
 *
 * <p>{@code confidence} 是 0~100 的整数，规则路径按命中槽位数累加；它只用于
 * 前端提示"可能理解有误，请确认"，**不参与排序**。</p>
 *
 * @param skill     技能，未识别为 {@code null}
 * @param priceMax  预算上限（分），未识别为 {@code null}
 * @param location  城市，未识别为 {@code null}
 * @param weekend   是否要求周末可接单，未识别为 {@code null}
 * @param headcount 需要人数，未识别为 {@code null}
 * @param keywords  其余关键词，用于词法召回；不为 {@code null}
 * @param confidence 理解置信度 0~100
 * @since V10
 */
public record QuerySlots(String skill, Long priceMax, String location, Boolean weekend,
                         Integer headcount, List<String> keywords, int confidence) {

    /** 规整 `keywords`，避免调用方传 null。 */
    public QuerySlots {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        if (confidence < 0 || confidence > 100) {
            throw new IllegalArgumentException("confidence 必须在 0~100");
        }
    }

    /** 没有任何有效槽位时，检索退化为普通关键词搜索。 */
    public boolean isEmpty() {
        return skill == null && priceMax == null && location == null
                && weekend == null && headcount == null && keywords.isEmpty();
    }
}
