package com.escrow.platform.ai.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 理由模板（V10-c4）：三段事实 + 一段口碑，全部从 {@link CandidateProfile} 拼装。
 *
 * <p>四项证据字段固定为 {@code skills / weekendAvailable / expectedUnitPrice / ratingX100}，
 * 与画像一一对应。模板<b>不会</b>写出画像里没有的内容——比如画像没有"过往项目"，
 * 就绝不出现"曾服务过某品牌"这类句子。这是可核率 1.00 的前提。</p>
 *
 * @since V10
 */
public final class ReasonTemplate {

    /** 拼装理由；候选人为空时返回空文本与空分段，调用方据此判定"无理由"。 */
    public ReasonDraft compose(CandidateProfile profile, QuerySlots slots) {
        List<ReasonDraft.ReasonSegment> segments = new ArrayList<>();

        if (slots.skill() != null && profile.hasSkill(slots.skill())) {
            segments.add(new ReasonDraft.ReasonSegment(
                    "技能匹配：你的「" + slots.skill() + "」与需求一致", "skills", slots.skill()));
        } else if (!profile.skills().isEmpty()) {
            String primary = profile.skills().get(0);
            segments.add(new ReasonDraft.ReasonSegment(
                    "技能相关：档案标注擅长「" + primary + "」", "skills", primary));
        }

        if (Boolean.TRUE.equals(slots.weekend()) && profile.weekendAvailable()) {
            segments.add(new ReasonDraft.ReasonSegment(
                    "档期匹配：档案标注周末可接单", "weekendAvailable", fieldValue(profile, "weekendAvailable")));
        }

        if (slots.priceMax() != null) {
            String price = fieldValue(profile, "expectedUnitPrice");
            String relation = profile.expectedUnitPrice() <= slots.priceMax() ? "不高于" : "高于";
            segments.add(new ReasonDraft.ReasonSegment(
                    "报价：" + (profile.expectedUnitPrice() / 100) + " 元，" + relation + "预算上限 "
                            + (slots.priceMax() / 100) + " 元", "expectedUnitPrice", price));
        }

        segments.add(new ReasonDraft.ReasonSegment(
                "口碑：完单 " + profile.completedOrders() + " 单，评分 " + ratingText(profile) + " 分",
                "ratingX100", fieldValue(profile, "ratingX100")));

        String text = segments.stream().map(ReasonDraft.ReasonSegment::text)
                .reduce((left, right) -> left + "；" + right).orElse("");
        return new ReasonDraft(text, segments);
    }

    /**
     * 逐段校验：证据值必须真的出现在画像对应字段里。
     *
     * <p>与 {@link ReasonDraft#verifiable()} 的区别：后者只看字段是否为空，
     * 本方法做真正的值匹配，用于评测的"可核率"。</p>
     */
    public boolean verify(CandidateProfile profile, ReasonDraft reason) {
        for (ReasonDraft.ReasonSegment segment : reason.segments()) {
            if (segment.evidenceField() == null) {
                return false;
            }
            String actual = fieldValue(profile, segment.evidenceField());
            if (actual == null || !actual.contains(segment.evidenceValue())) {
                return false;
            }
        }
        return true;
    }

    private String fieldValue(CandidateProfile profile, String field) {
        return switch (field) {
            case "skills" -> String.join(",", profile.skills());
            case "weekendAvailable" -> profile.weekendAvailable() ? "周末可接单" : "周末不可接单";
            case "expectedUnitPrice" -> String.valueOf(profile.expectedUnitPrice());
            case "ratingX100" -> ratingText(profile);
            case "completedOrders" -> String.valueOf(profile.completedOrders());
            default -> null;
        };
    }

    private String ratingText(CandidateProfile profile) {
        return String.format("%d.%02d", profile.ratingX100() / 100, profile.ratingX100() % 100);
    }
}
