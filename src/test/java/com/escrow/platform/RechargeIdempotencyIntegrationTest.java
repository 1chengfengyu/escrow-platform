package com.escrow.platform;

import com.escrow.platform.fund.application.FundAccountService;
import com.escrow.platform.fund.domain.AccountSubject;
import com.escrow.platform.fund.domain.FundJournalBatch;
import com.escrow.platform.fund.domain.FundJournalEntry;
import com.escrow.platform.fund.domain.JournalBizType;
import com.escrow.platform.fund.infrastructure.mapper.FundAccountMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalBatchMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalEntryMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 充值幂等：同一笔意图重复提交只生效一次（Issue #2）。
 *
 * <p>必须真库的原因：幂等由 {@code uk_fund_journal_batch_biz (biz_type, biz_no)}
 * 这个唯一索引执行，不是 Java 里的 {@code if (exists) return}。假 Mapper 里没有索引，
 * 也就无法证明「第二次 INSERT 一定会失败」——而这条缺陷恰好就在索引上。</p>
 *
 * <p>四条用例对应四个方向：①重复提交只算一次；②凭证不重复写；③不同意图必须算两次
 * （防过度幂等）；④并发同意图只有一个线程能占上位（互斥来自 InnoDB，不是应用层时序）。</p>
 *
 * <p>隔离方式：与 {@code V2EscrowFlowIntegrationTest} 一致，每个方法前后清空资金三表。
 * 不按 user_id 清理，因为充值会生成 SYSTEM 科目账户，按用户删不干净。</p>
 *
 * @since V2
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class RechargeIdempotencyIntegrationTest {

    private static final long EMPLOYER_ID = 1001L;
    private static final long AMOUNT = 5_000L;

    @Autowired
    private FundAccountService fundAccountService;
    @Autowired
    private FundAccountMapper fundAccountMapper;
    @Autowired
    private FundJournalBatchMapper fundJournalBatchMapper;
    @Autowired
    private FundJournalEntryMapper fundJournalEntryMapper;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        fundJournalEntryMapper.delete(null);
        fundJournalBatchMapper.delete(null);
        fundAccountMapper.delete(null);
    }

    /** 同一笔充值意图提交两次：可用余额只能增加一次。 */
    @Test
    void sameIntentShouldCreditOnlyOnce() {
        fundAccountService.recharge(EMPLOYER_ID, "idem-same", AMOUNT);
        fundAccountService.recharge(EMPLOYER_ID, "idem-same", AMOUNT);

        assertThat(fundAccountService.balances(EMPLOYER_ID).available())
                .as("重复提交同一笔充值后，可用余额应仍为单笔金额")
                .isEqualTo(AMOUNT);
    }

    /** 同一笔充值意图提交两次：凭证批次只能有一条，分录只能有两条。 */
    @Test
    void sameIntentShouldWriteOnlyOneJournalBatch() {
        fundAccountService.recharge(EMPLOYER_ID, "idem-batch", AMOUNT);
        fundAccountService.recharge(EMPLOYER_ID, "idem-batch", AMOUNT);

        List<FundJournalBatch> batches = fundJournalBatchMapper.selectList(null);
        assertThat(batches)
                .as("重复提交不应产生第二个凭证批次")
                .hasSize(1);
        assertThat(batches.get(0).getBizType()).isEqualTo(JournalBizType.RECHARGE);
        assertThat(batches.get(0).getBizNo()).isEqualTo("idem-batch");

        List<FundJournalEntry> entries = fundJournalEntryMapper.selectList(null);
        assertThat(entries)
                .as("一个充值批次应是两条分录（借发行 / 贷可用）")
                .hasSize(2);
    }

    /** 不同 requestId 是两笔独立充值：余额加两次，两个批次。防「过度幂等」。 */
    @Test
    void differentRequestIdsShouldCreditTwice() {
        fundAccountService.recharge(EMPLOYER_ID, "idem-a", AMOUNT);
        fundAccountService.recharge(EMPLOYER_ID, "idem-b", AMOUNT);

        assertThat(fundAccountService.balances(EMPLOYER_ID).available())
                .as("不同意图各算一笔，不能被误判成重复提交")
                .isEqualTo(AMOUNT * 2);
        assertThat(fundJournalBatchMapper.selectList(null)).hasSize(2);
    }

    /** 并发同 requestId：只有一次占位成功，余额只加一次。 */
    @Test
    void concurrentSameRequestIdShouldCreditOnlyOnce() throws Exception {
        // 先建好两个账户，让并发只竞争唯一索引，不夹带「并发首次建账户」的另一个竞态
        fundAccountService.getOrCreateUserAccount(EMPLOYER_ID, AccountSubject.ACC_AVAIL);
        fundAccountService.getOrCreateSystemAccount(AccountSubject.ACC_ISSUED);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                fundAccountService.recharge(EMPLOYER_ID, "idem-concurrent", AMOUNT);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(fundAccountService.balances(EMPLOYER_ID).available())
                .as("八个线程同时提交同一笔充值，余额只能加一次")
                .isEqualTo(AMOUNT);
        assertThat(fundJournalBatchMapper.selectList(null)).hasSize(1);
        assertThat(fundJournalEntryMapper.selectList(null)).hasSize(2);
    }
}
