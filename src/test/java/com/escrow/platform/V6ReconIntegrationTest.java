package com.escrow.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.escrow.platform.engagement.application.AcceptanceService;
import com.escrow.platform.engagement.application.ContractService;
import com.escrow.platform.engagement.application.HireService;
import com.escrow.platform.engagement.domain.AcceptanceDecision;
import com.escrow.platform.engagement.domain.ApplicationStatus;
import com.escrow.platform.engagement.domain.DeliverableSubmission;
import com.escrow.platform.engagement.domain.EngagementApplication;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementApplicationMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementContractMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementDeliverableMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementPerformanceRecordMapper;
import com.escrow.platform.fund.application.FundAccountService;
import com.escrow.platform.fund.domain.AccountOwnerType;
import com.escrow.platform.fund.domain.AccountSubject;
import com.escrow.platform.fund.domain.FundAccount;
import com.escrow.platform.fund.infrastructure.mapper.FundAccountMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowDetailMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowOrderMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalBatchMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalEntryMapper;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.settlement.application.ReconReport;
import com.escrow.platform.settlement.application.ReconRun;
import com.escrow.platform.settlement.application.ReconService;
import com.escrow.platform.settlement.domain.ChannelTxn;
import com.escrow.platform.settlement.domain.DifferenceStatus;
import com.escrow.platform.settlement.domain.DifferenceType;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.domain.ReconBatch;
import com.escrow.platform.settlement.domain.ReconBatchStatus;
import com.escrow.platform.settlement.domain.SettlementDifference;
import com.escrow.platform.settlement.infrastructure.channel.ChannelStatementSource;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import com.escrow.platform.settlement.infrastructure.mapper.ReconBatchMapper;
import com.escrow.platform.settlement.infrastructure.mapper.SettlementDifferenceMapper;
import com.escrow.platform.settlement.infrastructure.mapper.SettlementMapper;
import com.escrow.platform.task.application.TaskPublishService;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
import com.escrow.platform.task.infrastructure.mapper.TaskSlotMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账真库测试（V6，FR-RECON.JOB.01 / DIFF.01）：本地未知 + 通道成功 = LOST_RESPONSE。
 *
 * <p>通道流水由受控实现注入：把当天的「待查证」尝试伪造成通道已成功，
 * 这是模拟通道下构造差异的唯一手段，不把测试开关写进生产实现。</p>
 *
 * @since V6
 */
@SpringBootTest(properties = "escrow.payout.gateway-mode=TIMEOUT")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V6ReconIntegrationTest {

    private static final long OWNER_ID = 1201L;
    private static final LoginUser OWNER =
            new LoginUser(OWNER_ID, "owner-recon@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final long WORKER_ID = 2201L;
    private static final LoginUser WORKER =
            new LoginUser(WORKER_ID, "worker-recon@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final long UNIT_PRICE = 1_000L;
    /** 打款单金额是实付净额（单价 − 3% 服务费）。 */
    private static final long NET_AMOUNT = 970L;

    @Autowired
    private TaskPublishService taskPublishService;
    @Autowired
    private HireService hireService;
    @Autowired
    private ContractService contractService;
    @Autowired
    private AcceptanceService acceptanceService;
    @Autowired
    private FundAccountService fundAccountService;
    @Autowired
    private ReconService reconService;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private TaskSlotMapper taskSlotMapper;
    @Autowired
    private EngagementApplicationMapper applicationMapper;
    @Autowired
    private EngagementContractMapper contractMapper;
    @Autowired
    private EngagementDeliverableMapper deliverableMapper;
    @Autowired
    private EngagementPerformanceRecordMapper performanceRecordMapper;
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
    private SettlementMapper settlementMapper;
    @Autowired
    private PayoutOrderMapper payoutOrderMapper;
    @Autowired
    private ReconBatchMapper reconBatchMapper;
    @Autowired
    private SettlementDifferenceMapper settlementDifferenceMapper;

    /** 受控通道流水：把当天的未知尝试伪造成通道已成功。 */
    @TestConfiguration
    static class StatementConfig {

        @Bean
        @Primary
        ChannelStatementSource controllableStatement(PayoutOrderMapper payoutOrderMapper) {
            return date -> {
                List<PayoutOrder> attempts = payoutOrderMapper.selectList(
                        new LambdaQueryWrapper<PayoutOrder>()
                                .ge(PayoutOrder::getCreatedAt, date.atStartOfDay())
                                .lt(PayoutOrder::getCreatedAt, date.plusDays(1).atStartOfDay()));
                List<ChannelTxn> statement = new ArrayList<>();
                for (PayoutOrder attempt : attempts) {
                    if (attempt.getStatus() == PayoutStatus.PAYOUT_SUCCESS) {
                        statement.add(new ChannelTxn(attempt.getRequestNo(),
                                attempt.getActualAmount(), true));
                    } else if (attempt.getStatus() == PayoutStatus.PAYOUT_PENDING_VERIFICATION) {
                        statement.add(new ChannelTxn(attempt.getRequestNo(), attempt.getAmount(), true));
                    }
                }
                return statement;
            };
        }
    }

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        settlementDifferenceMapper.delete(null);
        reconBatchMapper.delete(null);
        fundJournalEntryMapper.delete(null);
        fundJournalBatchMapper.delete(null);
        fundEscrowDetailMapper.delete(null);
        fundEscrowOrderMapper.delete(null);
        fundAccountMapper.delete(null);
        payoutOrderMapper.delete(null);
        settlementMapper.delete(null);
        deliverableMapper.delete(null);
        performanceRecordMapper.delete(null);
        contractMapper.delete(null);
        applicationMapper.delete(null);
        taskSlotMapper.delete(null);
        taskMapper.delete(null);
    }

    /** 本地未知、通道成功：落 LOST_RESPONSE，批次 DONE 且恒等式平衡。 */
    @Test
    void lostResponseShouldBeIdentified() {
        Long contractId = hireAndDeliver(publishTask());
        var outcome = acceptanceService.accept(OWNER, contractId, AcceptanceDecision.PASS, null);
        PayoutOrder payout = payoutOrderMapper.selectById(outcome.payoutId());
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAYOUT_PENDING_VERIFICATION);

        ReconRun run = reconService.runDaily(LocalDate.now());

        assertThat(run.localCount()).isEqualTo(1);
        assertThat(run.channelCount()).isEqualTo(1);
        assertThat(run.diffCount()).isEqualTo(1);
        assertThat(run.diffAmount()).isEqualTo(NET_AMOUNT);
        assertThat(run.identityOk()).isTrue();

        SettlementDifference difference = settlementDifferenceMapper.selectOne(
                new LambdaQueryWrapper<SettlementDifference>()
                        .eq(SettlementDifference::getLocalRecordNo, payout.getRequestNo()));
        assertThat(difference.getDiffType()).isEqualTo(DifferenceType.LOST_RESPONSE);
        assertThat(difference.getStatus()).isEqualTo(DifferenceStatus.SETTLEMENT_DIFF_PENDING);
        assertThat(difference.getChannelAmount()).isEqualTo(NET_AMOUNT);
        assertThat(difference.getLocalAmount()).isNull();

        ReconBatch batch = reconBatchMapper.selectById(run.batchId());
        assertThat(batch.getStatus()).isEqualTo(ReconBatchStatus.RECON_DONE);
        assertThat(batch.getIdentityOk()).isEqualTo(1);

        // 同日重跑：不新增差异单
        ReconRun again = reconService.runDaily(LocalDate.now());
        assertThat(again.diffCount()).isEqualTo(1);
        assertThat(settlementDifferenceMapper.selectCount(null)).isEqualTo(1L);

        ReconReport report = reconService.reportOf(LocalDate.now());
        assertThat(report.pendingCount()).isEqualTo(1);
        assertThat(report.closingTotal() - report.turnover()).isEqualTo(report.openingTotal());
        assertThat(report.identityOk()).isTrue();
    }

    private Long publishTask() {
        fundAccountService.recharge(OWNER_ID, 10_000L);
        Task task = new Task();
        task.setEmployerId(OWNER_ID);
        task.setTitle("V6 对账测试任务-" + System.nanoTime());
        task.setDescription("对账差异识别");
        task.setCategory("地推");
        task.setUnitPrice(UNIT_PRICE);
        task.setSlotCount(1);
        task.setStartTime(LocalDateTime.now().plusHours(1));
        task.setEndTime(LocalDateTime.now().plusDays(2));
        task.setWorkLocation("北京市朝阳区");
        task.setPublishedAt(null);
        task.setCancelledAt(null);
        task.setRecruitingClosed(false);
        taskMapper.insert(task);
        taskPublishService.publish(OWNER, task.getId());
        return task.getId();
    }

    private Long hireAndDeliver(Long taskId) {
        EngagementApplication application = new EngagementApplication();
        application.setId(IdWorker.getId());
        application.setTaskId(taskId);
        application.setWorkerId(WORKER_ID);
        application.setStatus(ApplicationStatus.APPLY_PENDING);
        applicationMapper.insert(application);
        Long contractId = hireService.hire(OWNER, taskId, application.getId());
        contractService.start(WORKER, contractId);
        contractService.deliver(WORKER, contractId,
                DeliverableSubmission.from(List.of("https://img.example.com/v6-recon.jpg"), "已完成"));
        return contractId;
    }

    private long balanceOfSystem(AccountSubject subject) {
        FundAccount account = fundAccountMapper.selectOne(new LambdaQueryWrapper<FundAccount>()
                .eq(FundAccount::getOwnerType, AccountOwnerType.SYSTEM)
                .eq(FundAccount::getSubject, subject));
        return account == null ? 0L : account.getBalance();
    }
}
