package com.escrow.platform.ai.application;

import com.escrow.platform.ai.domain.QuerySlots;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 查询理解草稿（V10-c2）：解析结果先落这里，用户确认后才检索。
 *
 * <p>为什么必须有一层草稿：槽位回显是"理解错误可被当场拦下"的载体。没有草稿，
 * 用户改标签这个动作就无处落——只能重新解析一遍，改过的条件又会被模型覆盖。</p>
 *
 * <p>用进程内 Map 而不是 Redis：草稿只活 5 分钟、只有创建者可读，进程重启丢草稿的后果
 * 仅是"重新解析一次"，不值得为此引跨进程状态。单实例阶段这是正确取舍，
 * V8 多实例时再换 Redis，接口不变。</p>
 *
 * @since V10
 */
@Component
public class AiDraftStore {

    /** 草稿内容：槽位 + 原始问题（写 trace 用）。 */
    public record Draft(String draftId, QuerySlots slots, String queryText, long expiresAtMillis) {

        public boolean expired(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }

        /** 按用户删除的标签清空对应槽位；不改其他槽位。 */
        public QuerySlots applyRemovals(java.util.List<String> removedKeys) {
            if (removedKeys == null || removedKeys.isEmpty()) {
                return slots;
            }
            String skill = slots.skill();
            Long priceMax = slots.priceMax();
            String location = slots.location();
            Boolean weekend = slots.weekend();
            Integer headcount = slots.headcount();
            for (String key : removedKeys) {
                if (key == null) {
                    continue;
                }
                switch (key) {
                    case "skill" -> skill = null;
                    case "priceMax" -> priceMax = null;
                    case "location" -> location = null;
                    case "weekend" -> weekend = null;
                    case "headcount" -> headcount = null;
                    default -> {
                        // 未知键忽略：前端版本不一致时不应让检索整体失败
                    }
                }
            }
            return new QuerySlots(skill, priceMax, location, weekend, headcount, slots.keywords(), slots.confidence());
        }
    }

    private final Map<String, Draft> drafts = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public AiDraftStore(@Value("${escrow.ai.draft-ttl-seconds:300}") long ttlSeconds) {
        this.ttlMillis = Math.max(1, ttlSeconds) * 1000L;
    }

    /** 保存并返回草稿ID。 */
    public String save(QuerySlots slots, String queryText) {
        purgeExpired();
        String draftId = UUID.randomUUID().toString().replace("-", "");
        drafts.put(draftId, new Draft(draftId, slots, queryText, System.currentTimeMillis() + ttlMillis));
        return draftId;
    }

    /** 取草稿；不存在或已过期都返回空，调用方转 404 让用户重新解析。 */
    public Optional<Draft> find(String draftId) {
        if (draftId == null || draftId.isBlank()) {
            return Optional.empty();
        }
        Draft draft = drafts.get(draftId);
        if (draft == null || draft.expired(System.currentTimeMillis())) {
            drafts.remove(draftId);
            return Optional.empty();
        }
        return Optional.of(draft);
    }

    /** 清理过期草稿：防止长期运行后 Map 只增不减。 */
    private void purgeExpired() {
        long now = System.currentTimeMillis();
        drafts.entrySet().removeIf(entry -> entry.getValue().expired(now));
    }
}
