package com.escrow.platform.common.chat.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 私聊事件接线（V11，CARD-10，补遗 §2.1「自动开会话的时机」）。
 *
 * <p><b>提交后执行</b>：报名 / 录用的事务提交后才建会话，回滚时不会留下孤儿会话。
 * 无事务（如 {@code ApplicationService.apply} 当前无 {@code @Transactional}）时立即执行。</p>
 *
 * <p><b>不影响主业务</b>：会话创建失败只记 warn，绝不向上抛——
 * 报名与录用是主链路，私聊是旁路能力（对齐 {@code NotificationDispatcher}）。</p>
 *
 * @since V11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatEventDispatcher {

    private final ChatConversationService conversationService;

    /**
     * 报名插入成功后建 {@code APPLICATION} 会话（{@code bizId = 报名 ID}）。
     *
     * @param taskId        任务 ID
     * @param applicationId 报名 ID
     * @param employerId    任务雇主账号 ID
     * @param workerId      报名劳动者账号 ID
     */
    public void applicationCreated(Long taskId, Long applicationId, Long employerId, Long workerId) {
        runAfterCommit(() -> conversationService.ensureApplication(taskId, applicationId, employerId, workerId),
                "APPLICATION", applicationId);
    }

    /**
     * 录用 / 签约插入成功后建 {@code CONTRACT} 会话（{@code bizId = 签约 ID}）。
     *
     * <p>1 任务 N 名额时，每个签约各自一条会话，互不串。</p>
     *
     * @param taskId     任务 ID
     * @param contractId 签约 ID
     * @param employerId 任务雇主账号 ID
     * @param workerId   被录用劳动者账号 ID
     */
    public void contractCreated(Long taskId, Long contractId, Long employerId, Long workerId) {
        runAfterCommit(() -> conversationService.ensureContract(taskId, contractId, employerId, workerId),
                "CONTRACT", contractId);
    }

    /**
     * 任务到达终态后归档该任务全部会话（{@code OPEN -> ARCHIVED}，幂等）。
     *
     * @param taskId 任务 ID
     */
    public void archiveByTaskAfterCommit(Long taskId) {
        runAfterCommit(() -> conversationService.archiveByTask(taskId), "ARCHIVE", taskId);
    }

    private void runAfterCommit(Runnable action, String kind, Long bizId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runQuietly(action, kind, bizId);
                }
            });
        } else {
            runQuietly(action, kind, bizId);
        }
    }

    private void runQuietly(Runnable action, String kind, Long bizId) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.warn("会话事件处理失败，不影响业务 kind={} bizId={}", kind, bizId, ex);
        }
    }
}
