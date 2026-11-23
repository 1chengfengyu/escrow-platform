package com.escrow.platform.common.chat.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 私聊事件接线测试（V11，CARD-10）。
 *
 * <p>锁三件事：无事务立即执行、有事务等提交、失败只记日志不外抛。</p>
 *
 * @since V11
 */
@ExtendWith(MockitoExtension.class)
class ChatEventDispatcherTest {

    private static final long TASK_ID = 10L;
    private static final long BIZ_ID = 3001L;
    private static final long EMPLOYER_ID = 1001L;
    private static final long WORKER_ID = 2001L;

    @Mock
    private ChatConversationService conversationService;

    @InjectMocks
    private ChatEventDispatcher dispatcher;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 无事务（apply 当前无 @Transactional）：立即建 APPLICATION 会话。 */
    @Test
    void applicationCreatedWithoutTransactionShouldRunImmediately() {
        dispatcher.applicationCreated(TASK_ID, BIZ_ID, EMPLOYER_ID, WORKER_ID);

        verify(conversationService).ensureApplication(TASK_ID, BIZ_ID, EMPLOYER_ID, WORKER_ID);
    }

    /** 有事务：提交前不写会话，afterCommit 才执行。 */
    @Test
    void contractCreatedShouldWaitForCommit() {
        TransactionSynchronizationManager.initSynchronization();

        dispatcher.contractCreated(TASK_ID, BIZ_ID, EMPLOYER_ID, WORKER_ID);

        verify(conversationService, never()).ensureContract(TASK_ID, BIZ_ID, EMPLOYER_ID, WORKER_ID);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(conversationService).ensureContract(TASK_ID, BIZ_ID, EMPLOYER_ID, WORKER_ID);
    }

    /** 会话创建失败只记 warn，绝不把主业务打回滚。 */
    @Test
    void failureShouldBeSwallowed() {
        doThrow(new IllegalStateException("chat db down"))
                .when(conversationService).ensureApplication(TASK_ID, BIZ_ID, EMPLOYER_ID, WORKER_ID);

        assertThatCode(() -> dispatcher.applicationCreated(TASK_ID, BIZ_ID, EMPLOYER_ID, WORKER_ID))
                .doesNotThrowAnyException();
    }

    /** 任务终态：无事务时立即归档。 */
    @Test
    void archiveShouldRunImmediatelyWithoutTransaction() {
        dispatcher.archiveByTaskAfterCommit(TASK_ID);

        verify(conversationService).archiveByTask(TASK_ID);
    }
}
