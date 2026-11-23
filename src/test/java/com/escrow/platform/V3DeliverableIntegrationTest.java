package com.escrow.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.engagement.api.ContractListItemView;
import com.escrow.platform.engagement.application.ContractQueryService;
import com.escrow.platform.engagement.application.ContractService;
import com.escrow.platform.engagement.application.HireService;
import com.escrow.platform.engagement.domain.ApplicationStatus;
import com.escrow.platform.engagement.domain.ContractPageQuery;
import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.engagement.domain.DeliverableSubmission;
import com.escrow.platform.engagement.domain.EngagementApplication;
import com.escrow.platform.engagement.domain.EngagementContract;
import com.escrow.platform.engagement.domain.EngagementDeliverable;
import com.escrow.platform.engagement.domain.EngagementPerformanceRecord;
import com.escrow.platform.engagement.domain.PerformanceEventType;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementApplicationMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementContractMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementDeliverableMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementPerformanceRecordMapper;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.api.TaskDetailView;
import com.escrow.platform.task.application.TaskQueryService;
import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.domain.TaskDisplayStatus;
import com.escrow.platform.task.domain.TaskSlot;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
import com.escrow.platform.task.infrastructure.mapper.TaskSlotMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 交付物提交与「我的签约」的真库测试（v3-c4，FR-DELIVER.SUBMIT.01~.03 / NFR-SEC.02）。
 *
 * <p>只在 {@code ESCROW_IT=true} 且 MySQL 的 {@code escrow_test} 可用时运行。
 * 必须打真库：{@code UNIQUE(contract_id)} 与条件更新的线性化点，假 Mapper 证明不了。</p>
 *
 * @since V3
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V3DeliverableIntegrationTest {

    private static final long OWNER_ID = 1001L;
    private static final LoginUser OWNER =
            new LoginUser(OWNER_ID, "owner@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final LoginUser WORKER =
            new LoginUser(2001L, "worker1@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final LoginUser OTHER_WORKER =
            new LoginUser(2002L, "worker2@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Autowired
    private HireService hireService;
    @Autowired
    private ContractService contractService;
    @Autowired
    private ContractQueryService contractQueryService;
    @Autowired
    private TaskQueryService taskQueryService;
    @Autowired
    private EngagementApplicationMapper applicationMapper;
    @Autowired
    private EngagementContractMapper contractMapper;
    @Autowired
    private EngagementDeliverableMapper deliverableMapper;
    @Autowired
    private EngagementPerformanceRecordMapper performanceRecordMapper;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private TaskSlotMapper taskSlotMapper;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        deliverableMapper.delete(null);
        performanceRecordMapper.delete(null);
        contractMapper.delete(null);
        applicationMapper.delete(null);
        taskSlotMapper.delete(null);
        taskMapper.delete(null);
    }

    /** FR-DELIVER.SUBMIT.01~.03：签约与名额同时转「已交付」，写交付物与履约记录。 */
    @Test
    void deliverShouldAdvanceContractSlotAndPersistDeliverable() {
        Long taskId = insertPublishedTask(1);
        Long contractId = hireFirstSlot(taskId, WORKER);
        contractService.start(WORKER, contractId);

        ContractStatus status = contractService.deliver(WORKER, contractId, submission());

        assertThat(status).isEqualTo(ContractStatus.CONTRACT_DELIVERED);
        EngagementContract contract = contractMapper.selectById(contractId);
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.CONTRACT_DELIVERED);
        assertThat(contract.getDeliveredAt()).isNotNull();

        assertThat(onlySlotOf(taskId).getStatus()).isEqualTo(SlotStatus.SLOT_DELIVERED);

        EngagementDeliverable deliverable = deliverableOf(contractId);
        assertThat(deliverable.getWorkerId()).isEqualTo(WORKER.userId());
        assertThat(deliverable.getImageUrls()).isEqualTo("[\"https://img.example.com/1.jpg\"]");
        assertThat(deliverable.getDescription()).isEqualTo("已完成");
        assertThat(deliverable.getSubmittedAt()).isNotNull();

        assertThat(recordsOf(contractId)).extracting(EngagementPerformanceRecord::getEventType)
                .containsExactlyInAnyOrder(PerformanceEventType.PERFORMANCE_START,
                        PerformanceEventType.PERFORMANCE_DELIVER);
    }

    /** 交付后任务详情派生为「待验收」（FR-TASK.DETAIL.02 R8）。 */
    @Test
    void detailShouldShowPendingAcceptanceAfterDeliver() {
        Long taskId = insertPublishedTask(1);
        Long contractId = hireFirstSlot(taskId, WORKER);
        contractService.start(WORKER, contractId);
        contractService.deliver(WORKER, contractId, submission());

        TaskDetailView view = taskQueryService.detail(OTHER_WORKER, taskId);

        assertThat(view.status()).isEqualTo(TaskDisplayStatus.TASK_PENDING_ACCEPTANCE);
        assertThat(view.recruitingClosed()).isFalse();
    }

    /** 未开始履约就交付：409，签约 / 名额 / 交付物都不写。 */
    @Test
    void deliverShouldBeRejectedBeforeStart() {
        Long taskId = insertPublishedTask(1);
        Long contractId = hireFirstSlot(taskId, WORKER);

        ApiException ex = catchThrowableOfType(
                () -> contractService.deliver(WORKER, contractId, submission()), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(contractMapper.selectById(contractId).getStatus())
                .isEqualTo(ContractStatus.CONTRACT_ACTIVE);
        assertThat(onlySlotOf(taskId).getStatus()).isEqualTo(SlotStatus.SLOT_OCCUPIED);
        assertThat(deliverableMapper.selectCount(null)).isZero();
    }

    /** 一个签约至多一份交付物：唯一索引兜底并发 / 绕过守卫的重复写入。 */
    @Test
    void duplicateDeliverableShouldViolateUniqueKey() {
        Long taskId = insertPublishedTask(1);
        Long contractId = hireFirstSlot(taskId, WORKER);
        contractService.start(WORKER, contractId);
        contractService.deliver(WORKER, contractId, submission());

        EngagementDeliverable duplicate = new EngagementDeliverable();
        duplicate.setId(IdWorker.getId());
        duplicate.setContractId(contractId);
        duplicate.setWorkerId(WORKER.userId());
        duplicate.setImageUrls("[\"https://img.example.com/2.jpg\"]");
        duplicate.setSubmittedAt(LocalDateTime.now());

        assertThat(catchThrowableOfType(
                () -> deliverableMapper.insert(duplicate), DuplicateKeyException.class))
                .isNotNull();
        assertThat(deliverableMapper.selectCount(null)).isEqualTo(1L);
    }

    /** 数据权限第一层：我的签约只返回当前劳动者的签约。 */
    @Test
    void listMineShouldReturnOnlyCurrentWorkersContracts() {
        Long taskId = insertPublishedTask(2);
        Long myContractId = hireFirstSlot(taskId, WORKER);
        Long otherContractId = hireFirstSlot(taskId, OTHER_WORKER);

        PageView<ContractListItemView> mine =
                contractQueryService.listMine(WORKER, ContractPageQuery.from(null, null));

        assertThat(mine.items()).extracting(ContractListItemView::contractId)
                .containsExactly(myContractId);
        assertThat(mine.items()).noneMatch(item -> item.contractId().equals(otherContractId));
        assertThat(mine.total()).isEqualTo(1L);
    }

    private DeliverableSubmission submission() {
        return DeliverableSubmission.from(List.of("https://img.example.com/1.jpg"), "已完成");
    }

    private Long hireFirstSlot(Long taskId, LoginUser worker) {
        Long applicationId = insertPendingApplication(taskId, worker.userId());
        return hireService.hire(OWNER, taskId, applicationId);
    }

    private EngagementDeliverable deliverableOf(Long contractId) {
        return deliverableMapper.selectOne(new LambdaQueryWrapper<EngagementDeliverable>()
                .eq(EngagementDeliverable::getContractId, contractId));
    }

    private List<EngagementPerformanceRecord> recordsOf(Long contractId) {
        return performanceRecordMapper.selectList(new LambdaQueryWrapper<EngagementPerformanceRecord>()
                .eq(EngagementPerformanceRecord::getContractId, contractId));
    }

    private Long insertPublishedTask(int slotCount) {
        Task task = new Task();
        task.setId(IdWorker.getId());
        task.setEmployerId(OWNER_ID);
        task.setTitle("交付物测试任务");
        task.setDescription("用于验证交付物提交");
        task.setCategory("测试");
        task.setUnitPrice(1_000L);
        task.setSlotCount(slotCount);
        task.setStartTime(LocalDateTime.now().plusHours(1));
        task.setEndTime(LocalDateTime.now().plusDays(1));
        task.setWorkLocation("测试地点");
        task.setPublishedAt(LocalDateTime.now().minusHours(1));
        task.setRecruitingClosed(false);
        taskMapper.insert(task);

        for (int seqNo = 1; seqNo <= slotCount; seqNo++) {
            TaskSlot slot = new TaskSlot();
            slot.setId(IdWorker.getId());
            slot.setTaskId(task.getId());
            slot.setSeqNo(seqNo);
            slot.setStatus(SlotStatus.SLOT_AVAILABLE);
            taskSlotMapper.insert(slot);
        }
        return task.getId();
    }

    private Long insertPendingApplication(Long taskId, Long workerId) {
        EngagementApplication application = new EngagementApplication();
        application.setId(IdWorker.getId());
        application.setTaskId(taskId);
        application.setWorkerId(workerId);
        application.setStatus(ApplicationStatus.APPLY_PENDING);
        applicationMapper.insert(application);
        return application.getId();
    }

    private TaskSlot onlySlotOf(Long taskId) {
        return taskSlotMapper.selectOne(new LambdaQueryWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, taskId));
    }
}
