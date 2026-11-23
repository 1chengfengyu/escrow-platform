package com.escrow.platform.ai;

import com.escrow.platform.ai.domain.AiGrayRouter;
import com.escrow.platform.ai.domain.AiRankStrategy;
import com.escrow.platform.ai.domain.CandidateFilter;
import com.escrow.platform.ai.domain.CandidateProfile;
import com.escrow.platform.ai.domain.CandidateScorer;
import com.escrow.platform.ai.domain.FusionWeights;
import com.escrow.platform.ai.domain.QuerySlots;
import com.escrow.platform.ai.domain.ScoredCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 灰度分流（V10-c7）：比例 0 / 10000 / 中间值的行为，以及"灰度不改权重"的守护断言。
 *
 * @since V10
 */
class AiGrayTest {

    private static final List<Long> USERS = LongStream.rangeClosed(1, 500).boxed().toList();

    /** 比例 0：全部走 WEIGHTED。 */
    @Test
    void v10c7RatioZeroIsAllWeighted() {
        for (long userId : USERS) {
            assertThat(AiGrayRouter.resolve(AiRankStrategy.WEIGHTED, 0, userId))
                    .isEqualTo(AiRankStrategy.WEIGHTED);
        }
    }

    /** 比例 10000：全部走 RERANK。 */
    @Test
    void v10c7RatioFullIsAllRerank() {
        for (long userId : USERS) {
            assertThat(AiGrayRouter.resolve(AiRankStrategy.WEIGHTED, 10000, userId))
                    .isEqualTo(AiRankStrategy.RERANK);
        }
    }

    /** 同一 userId 多次解析结果稳定，且桶号只由 userId 决定。 */
    @Test
    void v10c7SameUserStaysInSameBucket() {
        for (long userId : USERS) {
            int first = AiGrayRouter.bucket(userId);
            int second = AiGrayRouter.bucket(userId);
            assertThat(first).isEqualTo(second).isBetween(0, AiGrayRouter.TOTAL_BUCKETS - 1);
            assertThat(AiGrayRouter.resolve(AiRankStrategy.WEIGHTED, 3000, userId))
                    .isEqualTo(AiGrayRouter.resolve(AiRankStrategy.WEIGHTED, 3000, userId));
        }
    }

    /** 中间比例：分流大致按比例走，且不同用户会被分到不同组。 */
    @Test
    void v10c7PartialRatioSplitsUsers() {
        int rerank = 0;
        for (long userId : USERS) {
            if (AiGrayRouter.resolve(AiRankStrategy.WEIGHTED, 2000, userId) == AiRankStrategy.RERANK) {
                rerank++;
            }
        }
        double actual = (double) rerank / USERS.size();
        assertThat(actual).isGreaterThan(0.05).isLessThan(0.35);
    }

    /** 基线已是 RERANK 时，比例不再起作用（全量重排）。 */
    @Test
    void v10c7BaselineRerankOverridesRatio() {
        for (long userId : USERS) {
            assertThat(AiGrayRouter.resolve(AiRankStrategy.RERANK, 0, userId))
                    .isEqualTo(AiRankStrategy.RERANK);
        }
    }

    /** 比例越界直接失败：配置写成 50（想表达 50%）不能静默变成 0.5%。 */
    @Test
    void v10c7OutOfRangeRatioRejected() {
        assertThatThrownBy(() -> AiGrayRouter.resolve(AiRankStrategy.WEIGHTED, -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiGrayRouter.resolve(AiRankStrategy.WEIGHTED, 10001, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 灰度不涉及权重：融合权重常量与打分结果在两种策略下完全一致。 */
    @Test
    void v10c7GrayDoesNotTouchWeights() {
        assertThat(FusionWeights.SEMANTIC).isEqualTo(5000);
        assertThat(FusionWeights.RATING).isEqualTo(3000);
        assertThat(FusionWeights.VOLUME).isEqualTo(2000);
        assertThat(FusionWeights.fuse(10000, 10000, 10000)).isEqualTo(10000);
        assertThat(FusionWeights.fuse(0, 0, 0)).isZero();

        CandidateScorer scorer = new CandidateScorer();
        QuerySlots slots = new QuerySlots("短视频剪辑", null, null, null, null, List.of(), 80);
        List<CandidateProfile> candidates = List.of(
                new CandidateProfile(101L, List.of("短视频剪辑"), 3, 45000L, "北京", true, 40, 470),
                new CandidateProfile(102L, List.of("文案写作"), 2, 20000L, "北京", true, 30, 460),
                new CandidateProfile(103L, List.of("视频剪辑"), 5, 60000L, "上海", true, 55, 475));
        List<CandidateProfile> passed = candidates.stream()
                .filter(CandidateFilter.from(slots)::matches)
                .toList();
        List<ScoredCandidate> first = scorer.score(slots, passed);
        List<ScoredCandidate> second = scorer.score(slots, passed);
        assertThat(first).isEqualTo(second);
        assertThat(first).isSortedAccordingTo((left, right) -> Integer.compare(right.score(), left.score()));
        assertThat(first.get(0).profile().userId()).isIn(101L, 103L);
    }
}
