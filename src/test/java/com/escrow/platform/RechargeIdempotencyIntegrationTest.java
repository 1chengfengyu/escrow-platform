package com.escrow.platform;

import com.escrow.platform.fund.application.FundAccountService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 充值幂等：同一笔意图重复提交只生效一次（Issue #2）。
 *
 * <p><strong>本类当前的用途是把「还不存在的契约」先写成可执行形式。</strong>
 * 两个用例在现有实现上都会失败，因为 {@code recharge} 没有幂等键参数，
 * 服务端无法区分「用户双击」和「用户充了两次」。它们不是回归测试，
 * 是这条 PR 要满足的验收条件；实现落地后应当转绿并永久留在库里。</p>
 *
 * <p>必须真库的原因：幂等由 {@code uk_fund_journal_batch_biz (biz_type, biz_no)}
 * 这个唯一索引执行，不是 Java 里的 {@code if (exists) return}。假 Mapper 里没有索引，
 * 也就无法证明「第二次 INSERT 一定会失败」。</p>
 *
 * <p>隔离方式：与 {@code V2EscrowFlowIntegrationTest} 一致，每个方法前后清空资金四表。
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
        fundAccountService.recharge(EMPLOYER_ID, AMOUNT);
        fundAccountService.recharge(EMPLOYER_ID, AMOUNT);

        assertThat(fundAccountService.balances(EMPLOYER_ID).available())
                .as("重复提交同一笔充值后，可用余额应仍为单笔金额")
                .isEqualTo(AMOUNT);
    }

    /** 同一笔充值意图提交两次：凭证批次只能有一条，分录只能有两条。 */
    @Test
    void sameIntentShouldWriteOnlyOneJournalBatch() {
        fundAccountService.recharge(EMPLOYER_ID, AMOUNT);
        fundAccountService.recharge(EMPLOYER_ID, AMOUNT);

        List<FundJournalBatch> batches = fundJournalBatchMapper.selectList(null);
        assertThat(batches)
                .as("重复提交不应产生第二个凭证批次")
                .hasSize(1);
        assertThat(batches.get(0).getBizType()).isEqualTo(JournalBizType.RECHARGE);

        List<FundJournalEntry> entries = fundJournalEntryMapper.selectList(null);
        assertThat(entries)
                .as("一个充值批次应是两条分录（借发行 / 贷可用）")
                .hasSize(2);
    }
}
