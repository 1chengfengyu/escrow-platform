package com.escrow.platform;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.fund.application.FundAccountService;
import com.escrow.platform.fund.domain.AccountSubject;
import com.escrow.platform.fund.domain.EscrowDetailStatus;
import com.escrow.platform.fund.domain.EscrowOrderStatus;
import com.escrow.platform.fund.domain.FundAccount;
import com.escrow.platform.fund.domain.FundEscrowDetail;
import com.escrow.platform.fund.domain.FundEscrowOrder;
import com.escrow.platform.fund.domain.FundJournalBatch;
import com.escrow.platform.fund.domain.FundJournalEntry;
import com.escrow.platform.fund.domain.JournalBizType;
import com.escrow.platform.fund.domain.JournalDirection;
import com.escrow.platform.fund.infrastructure.mapper.FundAccountMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowDetailMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowOrderMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalBatchMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalEntryMapper;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.application.TaskCancelService;
import com.escrow.platform.task.application.TaskExpiryService;
import com.escrow.platform.task.application.TaskPublishService;
import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.domain.TaskSlot;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
import com.escrow.platform.task.infrastructure.mapper.TaskSlotMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 发布 / 撤单 / 到期 / 并发冻结的真库集成测试（v2-c5 §6，裁决 4A）。
 *
 * <p>只在 {@code ESCROW_IT=true} 且 MySQL 的 {@code escrow_test} 可用时运行；
 * 默认 {@code mvn test} 不会连库，保持「MySQL 未启动也必须绿」。</p>
 *
 * <p>并发用例必须打真库：假 Mapper 无法证明「只有一笔成功」——
 * 条件更新的行锁语义只有真实 InnoDB 才能验证。</p>
 *
 * @since V2
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V2EscrowFlowIntegrationTest {

    private static final long EMPLOYER_ID = 1001L;
    private static final LoginUser EMPLOYER =
            new LoginUser(EMPLOYER_ID, "13800138000", UserRole.EMPLOYER, IdentityType.EMPLOYER);

    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private TaskSlotMapper taskSlotMapper;
    @Autowired
    private FundAccountMapper fundAccountMapper;
    @Autowired
    private FundEscrowOrderMapper fundEscrowOrderMapper;
    @Autowired
    private FundEscrowDetailMapper fundEscrowDetailMapper;
    @Autowired
    private FundJournalBatchMapper fundJournalBatchMapper;
    @Autowired
    private FundJournalEntryMapper fundJournalEntryMapper;
    @Autowired
    private FundAccountService fundAccountService;
    @Autowired
    private TaskPublishService taskPublishService;
    @Autowired
    private TaskCancelService taskCancelService;
    @Autowired
    private TaskExpiryService taskExpiryService;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        fundJournalEntryMapper.delete(null);
        fundJournalBatchMapper.delete(null);
        fundEscrowDetailMapper.delete(null);
        fundEscrowOrderMapper.delete(null);
        taskSlotMapper.delete(null);
        taskMapper.delete(null);
        fundAccountMapper.delete(null);
    }

    /** P1：发布 2 个名额 → 2 名额 + 1 托管单 + 2 明细 + 借贷相等 + 余额搬运。 */
    @Test
    void publishShouldFreezeSlotsAndBalanceJournal() {
        fundAccountService.recharge(EMPLOYER_ID, 20_000L);
        Long taskId = insertDraftTask(2, 5_000L, LocalDateTime.now().plusDays(2));

        taskPublishService.publish(EMPLOYER, taskId);

        List<TaskSlot> slots = taskSlotMapper.selectList(null).stream()
                .filter(s -> taskId.equals(s.getTaskId())).toList();
        assertThat(slots).hasSize(2);
        assertThat(slots).extracting(TaskSlot::getStatus).containsOnly(SlotStatus.SLOT_AVAILABLE);

        FundEscrowOrder order = fundEscrowOrderMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FundEscrowOrder>()
                        .eq(FundEscrowOrder::getTaskId, taskId));
        assertThat(order).isNotNull();
        assertThat(order.getTotalAmount()).isEqualTo(10_000L);
        assertThat(order.getFeeRate()).isEqualTo(300);
        assertThat(order.getStatus()).isEqualTo(EscrowOrderStatus.ESCROW_ORDER_HELD);

        assertThat(fundEscrowDetailMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FundEscrowDetail>()
                        .eq(FundEscrowDetail::getEscrowOrderId, order.getId()))).isEqualTo(2L);

        assertThat(balanceOf(AccountSubject.ACC_AVAIL)).isEqualTo(10_000L);
        assertThat(balanceOf(AccountSubject.ACC_FROZEN)).isEqualTo(10_000L);

        FundJournalBatch batch = fundJournalBatchMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FundJournalBatch>()
                        .eq(FundJournalBatch::getBizType, JournalBizType.ESCROW_HOLD)
                        .eq(FundJournalBatch::getBizNo, String.valueOf(taskId)));
        assertThat(batch).isNotNull();
        List<FundJournalEntry> entries = fundJournalEntryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FundJournalEntry>()
                        .eq(FundJournalEntry::getJournalBatchId, batch.getId()));
        long debit = entries.stream().filter(e -> e.getDirection() == JournalDirection.DEBIT)
                .mapToLong(FundJournalEntry::getAmount).sum();
        long credit = entries.stream().filter(e -> e.getDirection() == JournalDirection.CREDIT)
                .mapToLong(FundJournalEntry::getAmount).sum();
        assertThat(debit).isEqualTo(credit).isEqualTo(10_000L);
    }

    /** P4：同任务连打 5 次发布 → 名额 / 托管单 / 明细 / 批次都只有一份。 */
    @Test
    void publishShouldBeIdempotent() {
        fundAccountService.recharge(EMPLOYER_ID, 20_000L);
        Long taskId = insertDraftTask(2, 5_000L, LocalDateTime.now().plusDays(2));

        for (int i = 0; i < 5; i++) {
            taskPublishService.publish(EMPLOYER, taskId);
        }

        assertThat(taskSlotMapper.selectCount(null)).isEqualTo(2L);
        assertThat(fundEscrowOrderMapper.selectCount(null)).isEqualTo(1L);
        assertThat(fundEscrowDetailMapper.selectCount(null)).isEqualTo(2L);
        assertThat(fundJournalBatchMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FundJournalBatch>()
                        .eq(FundJournalBatch::getBizType, JournalBizType.ESCROW_HOLD))).isEqualTo(1L);
        assertThat(balanceOf(AccountSubject.ACC_AVAIL)).isEqualTo(10_000L);
    }

    /** P2：余额不足 → 名额 / 托管单 / 明细 / 批次全部为 0，余额不变。 */
    @Test
    void publishWithoutEnoughBalanceShouldRollbackEverything() {
        fundAccountService.recharge(EMPLOYER_ID, 5_000L);
        Long taskId = insertDraftTask(2, 5_000L, LocalDateTime.now().plusDays(2));

        assertThatThrownBy(() -> taskPublishService.publish(EMPLOYER, taskId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));

        assertThat(taskSlotMapper.selectCount(null)).isZero();
        assertThat(fundEscrowOrderMapper.selectCount(null)).isZero();
        assertThat(fundEscrowDetailMapper.selectCount(null)).isZero();
        assertThat(fundJournalBatchMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FundJournalBatch>()
                        .eq(FundJournalBatch::getBizType, JournalBizType.ESCROW_HOLD))).isZero();
        assertThat(balanceOf(AccountSubject.ACC_AVAIL)).isEqualTo(5_000L);
        assertThat(balanceOf(AccountSubject.ACC_FROZEN)).isZero();

        Task task = taskMapper.selectById(taskId);
        assertThat(task.getPublishedAt()).isNull();
    }

    /** P3：两线程争最后一份余额，成功数 = 1，另一笔必须 INSUFFICIENT_BALANCE。 */
    @Test
    void concurrentPublishShouldOnlySucceedOnce() throws Exception {
        fundAccountService.recharge(EMPLOYER_ID, 5_000L);
        Long first = insertDraftTask(1, 5_000L, LocalDateTime.now().plusDays(2));
        Long second = insertDraftTask(1, 5_000L, LocalDateTime.now().plusDays(2));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> a = pool.submit(() -> publishQuietly(start, first));
            Future<Boolean> b = pool.submit(() -> publishQuietly(start, second));
            start.countDown();

            boolean firstSucceeded = a.get(30, TimeUnit.SECONDS);
            boolean secondSucceeded = b.get(30, TimeUnit.SECONDS);

            assertThat(firstSucceeded ^ secondSucceeded)
                    .as("并发争抢最后一份余额时，必须恰好一笔成功")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(fundEscrowOrderMapper.selectCount(null)).isEqualTo(1L);
        assertThat(balanceOf(AccountSubject.ACC_AVAIL)).isZero();
        assertThat(balanceOf(AccountSubject.ACC_FROZEN)).isEqualTo(5_000L);
    }

    /** 撤单：全额退款 + 名额释放 + 托管单关闭；重复撤单不重复退款。 */
    @Test
    void cancelShouldRefundReleaseAndBeIdempotent() {
        fundAccountService.recharge(EMPLOYER_ID, 20_000L);
        Long taskId = insertDraftTask(2, 5_000L, LocalDateTime.now().plusDays(2));
        taskPublishService.publish(EMPLOYER, taskId);

        taskCancelService.cancel(EMPLOYER, taskId);
        taskCancelService.cancel(EMPLOYER, taskId);

        List<TaskSlot> slots = taskSlotMapper.selectList(null).stream()
                .filter(s -> taskId.equals(s.getTaskId())).toList();
        assertThat(slots).extracting(TaskSlot::getStatus).containsOnly(SlotStatus.SLOT_RELEASED);

        assertThat(balanceOf(AccountSubject.ACC_AVAIL)).isEqualTo(20_000L);
        assertThat(balanceOf(AccountSubject.ACC_FROZEN)).isZero();

        FundEscrowOrder order = fundEscrowOrderMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FundEscrowOrder>()
                        .eq(FundEscrowOrder::getTaskId, taskId));
        assertThat(order.getStatus()).isEqualTo(EscrowOrderStatus.ESCROW_ORDER_CLOSED);

        assertThat(fundJournalBatchMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FundJournalBatch>()
                        .eq(FundJournalBatch::getBizType, JournalBizType.ESCROW_REFUND)))
                .as("重复撤单不得重复写退款批次")
                .isEqualTo(1L);
    }

    /** 到期：收口招募、退款、释放；不写 cancelled_at。 */
    @Test
    void expiryShouldRefundAvailableSlots() {
        fundAccountService.recharge(EMPLOYER_ID, 20_000L);
        Long taskId = insertDraftTask(2, 5_000L, LocalDateTime.now().plusDays(2));
        taskPublishService.publish(EMPLOYER, taskId);

        // 发布后把时间窗整体挪到过去，模拟时间流逝（发布时不接受已过期的草稿）。
        // 不能只改 end_time：ck_task_time_range 要求 end_time > start_time，只挪一个会撞约束。
        LocalDateTime elapsed = LocalDateTime.now();
        taskMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Task>()
                .eq(Task::getId, taskId)
                .set(Task::getStartTime, elapsed.minusDays(2))
                .set(Task::getEndTime, elapsed.minusMinutes(1)));

        assertThat(taskExpiryService.expireTask(taskId)).isTrue();
        assertThat(taskExpiryService.expireTask(taskId)).isFalse();

        Task task = taskMapper.selectById(taskId);
        assertThat(task.getRecruitingClosed()).isTrue();
        assertThat(task.getCancelledAt()).isNull();

        assertThat(taskSlotMapper.selectList(null).stream()
                .filter(s -> taskId.equals(s.getTaskId())))
                .extracting(TaskSlot::getStatus).containsOnly(SlotStatus.SLOT_RELEASED);
        assertThat(balanceOf(AccountSubject.ACC_AVAIL)).isEqualTo(20_000L);
        assertThat(balanceOf(AccountSubject.ACC_FROZEN)).isZero();
    }

    /** 托管明细在发布后一律为已托管（防止状态机与 DDL 枚举值错位）。 */
    @Test
    void publishedDetailsShouldBeHeld() {
        fundAccountService.recharge(EMPLOYER_ID, 10_000L);
        Long taskId = insertDraftTask(1, 5_000L, LocalDateTime.now().plusDays(2));

        taskPublishService.publish(EMPLOYER, taskId);

        assertThat(fundEscrowDetailMapper.selectList(null))
                .extracting(FundEscrowDetail::getStatus)
                .containsOnly(EscrowDetailStatus.ESCROW_DETAIL_HELD);
    }

    private boolean publishQuietly(CountDownLatch start, Long taskId) {
        try {
            start.await(10, TimeUnit.SECONDS);
            taskPublishService.publish(EMPLOYER, taskId);
            return true;
        } catch (ApiException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long balanceOf(AccountSubject subject) {
        FundAccount account = fundAccountMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FundAccount>()
                        .eq(FundAccount::getUserId, EMPLOYER_ID)
                        .eq(FundAccount::getSubject, subject));
        return account == null ? 0L : account.getBalance();
    }

    private Long insertDraftTask(int slotCount, long unitPrice, LocalDateTime endTime) {
        LocalDateTime now = LocalDateTime.now();
        Task task = new Task();
        task.setEmployerId(EMPLOYER_ID);
        task.setTitle("集成测试任务-" + System.nanoTime());
        task.setDescription("发布托管集成测试");
        task.setCategory("地推");
        task.setUnitPrice(unitPrice);
        task.setSlotCount(slotCount);
        task.setStartTime(now.plusHours(1));
        task.setEndTime(endTime);
        task.setWorkLocation("北京市朝阳区");
        task.setPublishedAt(null);
        task.setCancelledAt(null);
        task.setRecruitingClosed(false);
        taskMapper.insert(task);
        return task.getId();
    }
}
