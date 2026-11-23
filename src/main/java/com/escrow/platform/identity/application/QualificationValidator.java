package com.escrow.platform.identity.application;

import com.escrow.platform.identity.api.QualificationReviewRequest;
import com.escrow.platform.identity.api.QualificationSubmitRequest;
import com.escrow.platform.identity.domain.ReviewStatus;
import com.escrow.platform.identity.domain.SubjectType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 雇主资质字段校验（FR-ACCT.QUAL.01 / FR-ADMIN.QUAL.01），纯 Java、无 Spring 依赖，可直接单测。
 *
 * <p>为什么不用 {@code @Valid}：与 c3 的 {@link RegisterValidator} 同一理由——
 * 框架抛的是 {@code BindException}，会被全局处理器兜成 {@code VALIDATION_FAILED}；
 * 而资质口的字段错误要求 {@code INVALID_PARAMETER} 且 {@code data.fields} 指明字段
 * （docs/decisions.md #30）。</p>
 *
 * <p>校验按 trim 后判断：主体名称前后空格不算错，但落库要存 trim 后的值（服务层负责），
 * 否则「长度合格但带前导空格」会撑破 {@code VARCHAR(128)}。</p>
 *
 * @since V1
 */
public class QualificationValidator {

    private static final Pattern ID_LAST4_PATTERN = Pattern.compile("^[0-9]{4}$");

    private static final int SUBJECT_NAME_MAX_LENGTH = 128;
    private static final int REVIEW_REASON_MAX_LENGTH = 255;

    private static final String SUBJECT_TYPE_MESSAGE = "主体类型只允许 INDIVIDUAL 或 SOLE_PROPRIETOR";
    private static final String SUBJECT_NAME_REQUIRED_MESSAGE = "主体名称不能为空";
    private static final String SUBJECT_NAME_TOO_LONG_MESSAGE = "主体名称长度不能超过 128";
    private static final String ID_LAST4_MESSAGE = "证件号后 4 位必须是 4 位数字";
    private static final String DECISION_MESSAGE = "decision 只允许 APPROVED 或 REJECTED";
    private static final String REASON_REQUIRED_MESSAGE = "驳回时理由不能为空";
    private static final String REASON_TOO_LONG_MESSAGE = "理由长度不能超过 255";

    /**
     * 校验提交字段。
     *
     * @param req 提交请求
     * @return 字段名 → 错误文案；空 Map 表示全部通过
     */
    public Map<String, String> validateSubmit(QualificationSubmitRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();

        String subjectType = req.subjectType() == null ? "" : req.subjectType().trim();
        if (!SubjectType.INDIVIDUAL.name().equals(subjectType)
                && !SubjectType.SOLE_PROPRIETOR.name().equals(subjectType)) {
            fields.put("subjectType", SUBJECT_TYPE_MESSAGE);
        }

        String subjectName = req.subjectName() == null ? "" : req.subjectName().trim();
        if (subjectName.isEmpty()) {
            fields.put("subjectName", SUBJECT_NAME_REQUIRED_MESSAGE);
        } else if (subjectName.length() > SUBJECT_NAME_MAX_LENGTH) {
            fields.put("subjectName", SUBJECT_NAME_TOO_LONG_MESSAGE);
        }

        String idLast4 = req.idLast4() == null ? "" : req.idLast4().trim();
        if (!ID_LAST4_PATTERN.matcher(idLast4).matches()) {
            fields.put("idLast4", ID_LAST4_MESSAGE);
        }

        return fields;
    }

    /**
     * 校验审核字段。
     *
     * @param req 审核请求
     * @return 字段名 → 错误文案；空 Map 表示全部通过
     */
    public Map<String, String> validateReview(QualificationReviewRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();

        String decision = req.decision() == null ? "" : req.decision().trim();
        boolean approved = ReviewStatus.APPROVED.name().equals(decision);
        boolean rejected = ReviewStatus.REJECTED.name().equals(decision);
        if (!approved && !rejected) {
            fields.put("decision", DECISION_MESSAGE);
        }

        String reason = req.reason() == null ? "" : req.reason().trim();
        if (rejected && reason.isEmpty()) {
            fields.put("reason", REASON_REQUIRED_MESSAGE);
        } else if (reason.length() > REVIEW_REASON_MAX_LENGTH) {
            fields.put("reason", REASON_TOO_LONG_MESSAGE);
        }

        return fields;
    }
}
