package com.escrow.platform.ai.domain;

/**
 * 融合打分权重（V10-c4）：语义 0.5 + 评分 0.3 + 完成量 0.2。
 *
 * <p><b>灰度期间禁止修改本类任何常量</b>。灰度只切换「是否启用重排」，
 * 一旦权重也变，A/B 的差异就归因不清——这是本版写死的实验设计约束。</p>
 *
 * <p>权重用万分位整数表示，计算全程整数，避免浮点误差；三项分数也统一到 0~10000。</p>
 *
 * @since V10
 */
public final class FusionWeights {

    /** 语义相关权重。 */
    public static final int SEMANTIC = 5000;

    /** 评分权重。 */
    public static final int RATING = 3000;

    /** 完成量权重。 */
    public static final int VOLUME = 2000;

    /** 满分刻度。 */
    public static final int SCALE = 10000;

    private FusionWeights() {
    }

    /** 三项加权求和，向下取整；入参越界直接拒绝，避免静默算错。 */
    public static int fuse(int semantic, int rating, int volume) {
        if (semantic < 0 || semantic > SCALE || rating < 0 || rating > SCALE || volume < 0 || volume > SCALE) {
            throw new IllegalArgumentException("分项得分必须在 0~10000");
        }
        return (semantic * SEMANTIC + rating * RATING + volume * VOLUME) / SCALE;
    }
}
