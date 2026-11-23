package com.escrow.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.infrastructure.mapper.NotificationInboxMapper;
import com.escrow.platform.common.notification.NotificationInbox;
import com.escrow.platform.common.notification.NotificationQueryService;
import com.escrow.platform.common.notification.api.NotificationView;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.engagement.api.ReputationView;
import com.escrow.platform.engagement.api.ReviewCreateRequest;
import com.escrow.platform.engagement.application.ReviewService;
import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.engagement.domain.EngagementContract;
import com.escrow.platform.engagement.domain.EngagementReview;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementContractMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementReviewMapper;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.PlatformUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.identity.domain.UserStatus;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserMapper;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 评价、信誉与站内信真库测试（V7，FR-SIGN.REVIEW.01 / FR-REPUTATION.01 / FR-NOTIFY.INBOX.01）。
 *
 * @since V7
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V7EngagementReviewIntegrationTest {

    private static final String WORKER_PHONE = "13900008881";
    private static final String EMPLOYER_PHONE = "13900008882";

    @Autowired
    private ReviewService reviewService;
    @Autowired
    private NotificationQueryService notificationQueryService;
    @Autowired
    private PlatformUserMapper platformUserMapper;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private EngagementContractMapper contractMapper;
    @Autowired
    private EngagementReviewMapper reviewMapper;
    @Autowired
    private NotificationInboxMapper notificationInboxMapper;

    private Long workerId;
    private Long employerId;
    private Long contractId;
    private String notificationEventId;

    private LoginUser worker;
    private LoginUser employer;

    @BeforeEach
    void setUp() {
        cleanUp();
        workerId = insertUser(WORKER_PHONE, "评价劳动者", UserRole.WORKER);
        employerId = insertUser(EMPLOYER_PHONE, "评价雇主", UserRole.EMPLOYER);
        worker = new LoginUser(workerId, WORKER_PHONE, UserRole.WORKER, IdentityType.WORKER);
        employer = new LoginUser(employerId, EMPLOYER_PHONE, UserRole.EMPLOYER, IdentityType.EMPLOYER);

        Task task = new Task();
        task.setEmployerId(employerId);
        task.setTitle("评价测试任务");
        task.setDescription("用于评价与信誉集成测试");
        task.setCategory("测试");
        task.setUnitPrice(10_000L);
        task.setSlotCount(1);
        task.setStartTime(LocalDateTime.now().minusDays(1));
        task.setEndTime(LocalDateTime.now().plusDays(1));
        task.setWorkLocation("线上");
        task.setPublishedAt(LocalDateTime.now().minusDays(1));
        taskMapper.insert(task);

        EngagementContract contract = new EngagementContract();
        contract.setTaskId(task.getId());
        contract.setSlotId(IdWorker.getId());
        contract.setApplicationId(IdWorker.getId());
        contract.setWorkerId(workerId);
        contract.setStatus(ContractStatus.CONTRACT_COMPLETED);
        contract.setEffectiveAt(LocalDateTime.now().minusDays(1));
        contractMapper.insert(contract);
        contractId = contract.getId();

        notificationEventId = "it-review-" + UUID.randomUUID();
        NotificationInbox inbox = new NotificationInbox();
        inbox.setEventId(notificationEventId);
        inbox.setUserId(workerId);
        inbox.setBizType("SETTLEMENT");
        inbox.setBizNo("S001");
        inbox.setTitle("结算完成");
        inbox.setContent("款项已到账");
        inbox.setReadFlag(0);
        inbox.setCreatedAt(LocalDateTime.now());
        notificationInboxMapper.insert(inbox);
    }

    @AfterEach
    void cleanUp() {
        if (notificationEventId != null) {
            notificationInboxMapper.delete(new LambdaQueryWrapper<NotificationInbox>()
                    .eq(NotificationInbox::getEventId, notificationEventId));
            notificationEventId = null;
        }
        if (contractId != null) {
            reviewMapper.delete(new LambdaQueryWrapper<EngagementReview>()
                    .eq(EngagementReview::getContractId, contractId));
        }
        if (workerId != null && employerId != null) {
            // 任务与签约按「双方 ID + 完成态」清理，避免误删其他用例数据
            List<EngagementContract> contracts = contractMapper.selectList(
                    new LambdaQueryWrapper<EngagementContract>()
                            .eq(EngagementContract::getWorkerId, workerId)
                            .eq(EngagementContract::getStatus, ContractStatus.CONTRACT_COMPLETED));
            for (EngagementContract contract : contracts) {
                contractMapper.deleteById(contract.getId());
                taskMapper.deleteById(contract.getTaskId());
            }
            platformUserMapper.deleteById(workerId);
            platformUserMapper.deleteById(employerId);
        }
        workerId = null;
        employerId = null;
        contractId = null;
        SecurityContextHolder.clearContext();
    }

    /** 劳动者评价雇主落库，重复评价 409，信誉汇总反映已收评价。 */
    @Test
    void reviewShouldPersistAndDuplicateShouldConflict() {
        loginAs(worker);
        reviewService.create(worker, contractId, new ReviewCreateRequest(5, "结算及时"));

        List<EngagementReview> rows = reviewMapper.selectList(new LambdaQueryWrapper<EngagementReview>()
                .eq(EngagementReview::getContractId, contractId)
                .eq(EngagementReview::getReviewerId, workerId));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRevieweeId()).isEqualTo(employerId);
        assertThat(rows.get(0).getRating()).isEqualTo(5);

        assertThatThrownBy(() -> reviewService.create(worker, contractId,
                new ReviewCreateRequest(4, "再评一次")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        loginAs(employer);
        reviewService.create(employer, contractId, new ReviewCreateRequest(4, "交付合格"));
    }

    /** 信誉：完成数、评价数、平均分与分页。 */
    @Test
    void reputationShouldAggregateRealRows() {
        loginAs(employer);
        reviewService.create(employer, contractId, new ReviewCreateRequest(4, "交付合格"));

        ReputationView view = reviewService.reputation(workerId, 1, 10);

        assertThat(view.completedContracts()).isGreaterThanOrEqualTo(1L);
        assertThat(view.reviewCount()).isGreaterThanOrEqualTo(1L);
        assertThat(view.averageRatingX100()).isGreaterThanOrEqualTo(400);
        assertThat(view.reviews().items()).isNotEmpty();
    }

    /** 站内信：只能看自己的，标记已读后未读数下降，别人的消息 404。 */
    @Test
    void notificationShouldBeOwnedAndReadable() {
        loginAs(worker);
        PageView<NotificationView> page = notificationQueryService.list(worker, 0, 1, 20);
        assertThat(page.items()).anyMatch(item -> "S001".equals(item.bizNo()));

        long unreadBefore = notificationQueryService.unreadCount(worker);
        assertThat(unreadBefore).isGreaterThanOrEqualTo(1L);

        Long notificationId = notificationInboxMapper.selectList(
                        new LambdaQueryWrapper<NotificationInbox>()
                                .eq(NotificationInbox::getEventId, notificationEventId))
                .get(0).getId();
        notificationQueryService.markRead(worker, notificationId);
        assertThat(notificationQueryService.unreadCount(worker)).isEqualTo(unreadBefore - 1);

        loginAs(employer);
        assertThatThrownBy(() -> notificationQueryService.markRead(employer, notificationId))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private Long insertUser(String phone, String nickname, UserRole role) {
        PlatformUser user = new PlatformUser();
        user.setPhone(phone);
        user.setEmail(phone + "@example.com");
        user.setNickname(nickname);
        user.setPasswordHash("$2A$10$abcdefghijklmnopqrstuv");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        platformUserMapper.insert(user);
        return user.getId();
    }

    private static void loginAs(LoginUser principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
