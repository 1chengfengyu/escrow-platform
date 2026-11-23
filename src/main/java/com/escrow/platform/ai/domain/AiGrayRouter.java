package com.escrow.platform.ai.domain;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * 灰度分流器（V10-c7）：按 {@code userId} 哈希取模决定走 WEIGHTED 还是 RERANK。
 *
 * <p>为什么用 CRC32 而不是 {@code String.hashCode()}：后者虽在 JDK 里稳定，
 * 但只是约定实现；CRC32 是跨语言、跨版本都一致的标准算法，评测脚本与线上
 * 才能算出同一个桶。桶数固定 10000，与 {@code gray-ratio} 的万分比口径对齐。</p>
 *
 * <p>稳定性要求：同一 userId 在任何机器、任何重启后都必须落在同一桶，
 * 所以这里**只用 userId 一个输入**，不掺时间、不掺随机数。</p>
 *
 * @since V10
 */
public final class AiGrayRouter {

    /** 桶总数，与万分比一一对应。 */
    public static final int TOTAL_BUCKETS = 10000;

    private AiGrayRouter() {
    }

    /** 桶号 0~9999。 */
    public static int bucket(long userId) {
        CRC32 crc = new CRC32();
        crc.update(String.valueOf(userId).getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % TOTAL_BUCKETS);
    }

    /**
     * 解析本次请求应使用的策略。
     *
     * <p>语义：{@code baseline=RERANK} 表示强制全量重排（等价于 ratio=10000）；
     * {@code baseline=WEIGHTED} 时按 ratio 分流。ratio 越界直接抛错，
     * 避免配置写错（如 50 想表达 50%）时静默只放 0.5%。</p>
     *
     * @param baseline 全局基线策略
     * @param grayRatio 灰度比例，万分比 0~10000
     * @param userId   请求方用户ID
     */
    public static AiRankStrategy resolve(AiRankStrategy baseline, int grayRatio, long userId) {
        if (grayRatio < 0 || grayRatio > TOTAL_BUCKETS) {
            throw new IllegalArgumentException("gray-ratio 必须在 0~10000");
        }
        if (baseline == AiRankStrategy.RERANK) {
            return AiRankStrategy.RERANK;
        }
        if (grayRatio == 0) {
            return AiRankStrategy.WEIGHTED;
        }
        if (grayRatio == TOTAL_BUCKETS) {
            return AiRankStrategy.RERANK;
        }
        return bucket(userId) < grayRatio ? AiRankStrategy.RERANK : AiRankStrategy.WEIGHTED;
    }
}
