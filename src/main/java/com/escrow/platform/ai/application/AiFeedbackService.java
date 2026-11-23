package com.escrow.platform.ai.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.ai.infrastructure.entity.AiFeedbackEntity;
import com.escrow.platform.ai.infrastructure.entity.AiTraceEntity;
import com.escrow.platform.ai.infrastructure.mapper.AiFeedbackMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 检索反馈（V10-c6/c7）：点击与二次搜索的幂等上报。
 *
 * <p>幂等靠唯一键 {@code (trace_no, user_id)}，不是"先查再插"：并发上报时后者会撞键，
 * 撞键说明已经记过，改成置位即可。**重复上报不累加**——这里没有计数器，
 * 只有布尔标记，把"一个人点十次"与"十个人各点一次"彻底区分开。</p>
 *
 * <p>二次搜索只在链路创建后 60 秒窗口内记一次：超窗口的二次搜索属于新需求，
 * 记进来会把"没搜到"的信号冲淡。</p>
 *
 * @since V10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiFeedbackService {

    /** 二次搜索统计窗口。 */
    private static final long SECOND_SEARCH_WINDOW_SECONDS = 60;

    private final AiFeedbackMapper feedbackMapper;
    private final AiTraceService traceService;

    /**
     * 上报反馈。
     *
     * @param current      会话主体
     * @param traceNo      链路号
     * @param clicked      是否点击了结果
     * @param secondSearch 是否在窗口内二次搜索（超窗口忽略）
     */
    public void record(LoginUser current, String traceNo, boolean clicked, boolean secondSearch) {
        AiTraceEntity trace = traceService.require(traceNo);
        if (!trace.getUserId().equals(current.userId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "只能上报自己的检索反馈");
        }
        boolean second = secondSearch && withinWindow(trace.getCreatedAt());

        AiFeedbackEntity existing = feedbackMapper.selectOne(new LambdaQueryWrapper<AiFeedbackEntity>()
                .eq(AiFeedbackEntity::getTraceNo, traceNo)
                .eq(AiFeedbackEntity::getUserId, current.userId()));
        if (existing != null) {
            merge(existing, clicked, second);
            return;
        }

        AiFeedbackEntity entity = new AiFeedbackEntity();
        entity.setTraceNo(traceNo);
        entity.setUserId(current.userId());
        entity.setClicked(clicked);
        entity.setSecondSearch(second);
        entity.setUpdatedAt(LocalDateTime.now());
        try {
            feedbackMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            // 并发首次上报：对方已插入，回查后按置位合并
            AiFeedbackEntity winner = feedbackMapper.selectOne(new LambdaQueryWrapper<AiFeedbackEntity>()
                    .eq(AiFeedbackEntity::getTraceNo, traceNo)
                    .eq(AiFeedbackEntity::getUserId, current.userId()));
            if (winner == null) {
                throw ex;
            }
            merge(winner, clicked, second);
        }
    }

    private void merge(AiFeedbackEntity existing, boolean clicked, boolean second) {
        boolean nextClicked = Boolean.TRUE.equals(existing.getClicked()) || clicked;
        boolean nextSecond = Boolean.TRUE.equals(existing.getSecondSearch()) || second;
        if (nextClicked == Boolean.TRUE.equals(existing.getClicked())
                && nextSecond == Boolean.TRUE.equals(existing.getSecondSearch())) {
            return;
        }
        feedbackMapper.update(null, new LambdaUpdateWrapper<AiFeedbackEntity>()
                .eq(AiFeedbackEntity::getId, existing.getId())
                .set(AiFeedbackEntity::getClicked, nextClicked)
                .set(AiFeedbackEntity::getSecondSearch, nextSecond)
                .set(AiFeedbackEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private boolean withinWindow(LocalDateTime createdAt) {
        if (createdAt == null) {
            return false;
        }
        return Duration.between(createdAt, LocalDateTime.now()).getSeconds() <= SECOND_SEARCH_WINDOW_SECONDS;
    }
}
