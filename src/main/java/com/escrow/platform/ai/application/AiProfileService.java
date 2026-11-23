package com.escrow.platform.ai.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.ai.domain.InjectionGuard;
import com.escrow.platform.ai.domain.ProfileFieldSource;
import com.escrow.platform.ai.infrastructure.entity.AiWorkerProfileEntity;
import com.escrow.platform.ai.infrastructure.mapper.AiWorkerProfileMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.identity.domain.LoginUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务者画像（V10-c3）：AI 检索与推荐理由的唯一事实来源。
 *
 * <p>冷启动必须可写：新服务者只填技能、没有报价，也要能保存、接口不报错。
 * 缺的字段标 {@link ProfileFieldSource#MISSING}，前端据此显示"未填写"而不是 0 元 / 0 分。</p>
 *
 * <p>技能是用户自由文本，落库前一律过 {@link InjectionGuard#sanitizeTags}——
 * 这是间接提示注入的第一道防线（第二、三层在检索链路里）。</p>
 *
 * @since V10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProfileService {

    private static final String FIELD_SKILLS = "skills";
    private static final String FIELD_EXPERIENCE = "experienceYears";
    private static final String FIELD_PRICE = "expectedUnitPrice";
    private static final String FIELD_LOCATION = "workLocation";
    private static final String FIELD_WEEKEND = "weekendAvailable";
    private static final String FIELD_ORDERS = "completedOrders";
    private static final String FIELD_RATING = "ratingX100";

    private final AiWorkerProfileMapper profileMapper;
    private final InjectionGuard injectionGuard;
    private final ObjectMapper objectMapper;

    /**
     * 写入（或更新）当前登录服务者的画像。
     *
     * <p>完单数与评分不接受用户输入：它们要么来自业务派生（DERIVED），要么缺失（MISSING），
     * 允许用户改会让"评分"失去意义。</p>
     */
    public AiResults.Profile upsert(LoginUser current, AiResults.ProfileCommand command) {
        long userId = current.userId();
        List<String> skills = injectionGuard.sanitizeTags(command.skills());
        AiWorkerProfileEntity existing = findEntity(userId);
        Map<String, ProfileFieldSource> sources = existing == null
                ? new LinkedHashMap<>()
                : readSources(existing.getSourceFlags());

        putSource(sources, FIELD_SKILLS, !skills.isEmpty());
        putSource(sources, FIELD_EXPERIENCE, command.experienceYears() != null);
        putSource(sources, FIELD_PRICE, command.expectedUnitPrice() != null);
        putSource(sources, FIELD_LOCATION, command.location() != null && !command.location().isBlank());
        putSource(sources, FIELD_WEEKEND, command.weekendAvailable() != null);
        if (existing == null || existing.getCompletedOrders() == null) {
            sources.put(FIELD_ORDERS, ProfileFieldSource.MISSING);
        }
        if (existing == null || existing.getRatingX100() == null) {
            sources.put(FIELD_RATING, ProfileFieldSource.MISSING);
        }

        String sourceJson = writeSources(sources);
        if (existing == null) {
            AiWorkerProfileEntity entity = new AiWorkerProfileEntity();
            entity.setUserId(userId);
            entity.setSkills(String.join(",", skills));
            entity.setExperienceYears(command.experienceYears());
            entity.setExpectedUnitPrice(command.expectedUnitPrice());
            entity.setWorkLocation(blankToNull(command.location()));
            entity.setAvailableWeekend(command.weekendAvailable());
            entity.setSourceFlags(sourceJson);
            entity.setProfileVersion(1);
            entity.setUpdatedAt(LocalDateTime.now());
            profileMapper.insert(entity);
            return toResult(entity);
        }

        int nextVersion = (existing.getProfileVersion() == null ? 1 : existing.getProfileVersion()) + 1;
        // updateById 会忽略 null，冷启动"清空报价"这类需求就做不到，必须显式 set
        LambdaUpdateWrapper<AiWorkerProfileEntity> update = new LambdaUpdateWrapper<AiWorkerProfileEntity>()
                .eq(AiWorkerProfileEntity::getId, existing.getId())
                .set(AiWorkerProfileEntity::getSkills, String.join(",", skills))
                .set(AiWorkerProfileEntity::getExperienceYears, command.experienceYears())
                .set(AiWorkerProfileEntity::getExpectedUnitPrice, command.expectedUnitPrice())
                .set(AiWorkerProfileEntity::getWorkLocation, blankToNull(command.location()))
                .set(AiWorkerProfileEntity::getAvailableWeekend, command.weekendAvailable())
                .set(AiWorkerProfileEntity::getSourceFlags, sourceJson)
                .set(AiWorkerProfileEntity::getProfileVersion, nextVersion);
        profileMapper.update(null, update);

        existing.setSkills(String.join(",", skills));
        existing.setExperienceYears(command.experienceYears());
        existing.setExpectedUnitPrice(command.expectedUnitPrice());
        existing.setWorkLocation(blankToNull(command.location()));
        existing.setAvailableWeekend(command.weekendAvailable());
        existing.setSourceFlags(sourceJson);
        existing.setProfileVersion(nextVersion);
        return toResult(existing);
    }

    /** 读当前服务者画像；没填过转 404，不编造一份空画像。 */
    public AiResults.Profile me(LoginUser current) {
        AiWorkerProfileEntity entity = findEntity(current.userId());
        if (entity == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "尚未填写服务者画像");
        }
        return toResult(entity);
    }

    private AiWorkerProfileEntity findEntity(long userId) {
        return profileMapper.selectOne(new LambdaQueryWrapper<AiWorkerProfileEntity>()
                .eq(AiWorkerProfileEntity::getUserId, userId));
    }

    private void putSource(Map<String, ProfileFieldSource> sources, String field, boolean provided) {
        sources.put(field, provided ? ProfileFieldSource.USER_INPUT : ProfileFieldSource.MISSING);
    }

    private Map<String, ProfileFieldSource> readSources(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> raw = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
            Map<String, ProfileFieldSource> sources = new LinkedHashMap<>();
            raw.forEach((key, value) -> {
                try {
                    sources.put(key, ProfileFieldSource.valueOf(value));
                } catch (IllegalArgumentException ex) {
                    sources.put(key, ProfileFieldSource.MISSING);
                }
            });
            return sources;
        } catch (Exception ex) {
            log.warn("source_flags 解析失败，按缺失处理", ex);
            return new LinkedHashMap<>();
        }
    }

    private String writeSources(Map<String, ProfileFieldSource> sources) {
        Map<String, String> raw = new LinkedHashMap<>();
        sources.forEach((key, value) -> raw.put(key, value.name()));
        try {
            return objectMapper.writeValueAsString(raw);
        } catch (Exception ex) {
            log.warn("source_flags 序列化失败，写空对象", ex);
            return "{}";
        }
    }

    private AiResults.Profile toResult(AiWorkerProfileEntity entity) {
        Map<String, String> sources = new LinkedHashMap<>();
        readSources(entity.getSourceFlags()).forEach((key, value) -> sources.put(key, value.name()));
        return new AiResults.Profile(entity.getUserId(), splitSkills(entity.getSkills()),
                entity.getExperienceYears(), entity.getExpectedUnitPrice(), entity.getWorkLocation(),
                entity.getAvailableWeekend(), entity.getCompletedOrders(), entity.getRatingX100(),
                sources, entity.getProfileVersion() == null ? 1 : entity.getProfileVersion());
    }

    private List<String> splitSkills(String skills) {
        if (skills == null || skills.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(skills.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
