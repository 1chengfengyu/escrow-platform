package com.escrow.platform.task.domain;

/**
 * 撤单资格分类（v2-c2 细化裁决，卡片 §1.3 第 7 条），<b>不落库</b>。
 *
 * <p>判据是 {@link TaskStatusFacts} 的名额集合，由
 * {@link TaskCancelEligibilityClassifier#classify} 计算；执行撤单（退款 + 释放名额）归 v2-c5。</p>
 *
 * <p>与 SRS 错误码的映射（v2-c5 抛码时用）：</p>
 * <ul>
 *   <li>{@link #HAS_ACTIVE_SLOT} → {@code TASK_HAS_ACTIVE_SLOT}（SRS FR-TASK.CANCEL.02，
 *       存在非终态名额时拒绝撤单）</li>
 *   <li>{@link #NOT_CANCELABLE} → {@code TASK_NOT_CANCELABLE}（SRS FR-TASK.CANCEL.02，
 *       全部名额已为终态 / 草稿 / 零名额等不可撤情形）</li>
 *   <li>{@link #CANCELABLE} → 不抛码，允许撤单</li>
 * </ul>
 *
 * <p>这两个错误码本卡不新增（卡片 §6.3），届时按 SRS 一次加齐。</p>
 *
 * @since V2
 */
public enum TaskCancelEligibility {

    /** 可撤单：全部名额均为待占用（SRS FR-TASK.CANCEL.01 的前提）。 */
    CANCELABLE,

    /** 不可撤：存在非终态且非待占用的名额（已占用 / 履约中 / 已交付 / 待裁决 / 待结算）。 */
    HAS_ACTIVE_SLOT,

    /** 不可撤：草稿 / 零名额，或「部分待占用 + 部分终态」的混合情形（FR-TASK.CANCEL.01 要求全部名额均为待占用）。 */
    NOT_CANCELABLE
}
