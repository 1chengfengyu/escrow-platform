package com.escrow.platform.common.chat.application;

import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.chat.api.ConversationListItemView;
import com.escrow.platform.common.chat.api.ConversationView;
import com.escrow.platform.common.chat.api.CreateConversationRequest;
import com.escrow.platform.common.chat.domain.ChatConversation;
import com.escrow.platform.common.chat.domain.ChatConversationStateMachine;
import com.escrow.platform.common.chat.domain.ChatConversationStatus;
import com.escrow.platform.common.chat.domain.ChatScope;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatConversationMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.application.AccountStatusQueryService;
import com.escrow.platform.identity.application.IdentityProfileService;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.task.application.TaskParticipationService;
import com.escrow.platform.task.application.TaskQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 会话服务（V11，FR-MSG.CHAT.01，补遗 §2.1 / §2.2 / §5.1 / §5.2）。
 *
 * <p><b>开会话靠唯一键兜底</b>：INSERT 撞 {@code uk_chat_conv_scope_task_biz_pair} 后回查既有行，
 * 不做「先 SELECT 判断再 INSERT」（project_rules §6.2.8）。同一任务同一对双方只有一个咨询会话。</p>
 *
 * <p>跨域只走 Service 入口：任务归属与时间走 {@link TaskParticipationService}，任务标题走
 * {@link TaskQueryService}，账号状态走 {@link AccountStatusQueryService}——不直查 {@code task} /
 * {@code platform_user} 表（project_rules §3）。</p>
 *
 * <p><b>查询不标 {@code readOnly=true}</b>：非当事人访问要写审计，只读事务会让审计插入失败
 * （CARD-05 风险清单第 5 条）。</p>
 *
 * <p>本类不新增跨域事务：{@code ensureApplication/ensureContract} 只做单表插入，由
 * CARD-10 的 {@code afterCommit} 回调调用。</p>
 *
 * @since V11
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatConversationService {

    /** 列表页大小上限；MyBatis-Plus 的 maxLimit=100，这里按补遗 §2.3 再夹到 50。 */
    private static final int MAX_PAGE_SIZE = 50;

    /** INQUIRY 的 biz_id 固定为 0：NULL 不参与唯一索引去重（补遗 §3 设计要点）。 */
    private static final long INQUIRY_BIZ_ID = 0L;

    /** 系统触发（报名 / 录用事件）的发起人占位。 */
    private static final long SYSTEM_INITIATOR_ID = 0L;

    private static final String AUDIT_BIZ_TYPE = "CHAT";
    private static final String AUDIT_ACTION_FORBIDDEN = "FORBIDDEN_VIEW";
    private static final String UNAUTHENTICATED_MESSAGE = "请先登录";
    private static final String FORBIDDEN_MESSAGE = "无权访问该会话";
    private static final String NOT_FOUND_MESSAGE = "会话不存在";
    private static final String TASK_NOT_RECRUITING_MESSAGE = "任务不在招募中，无法发起咨询";

    private final ChatConversationMapper conversationMapper;
    /** 任务归属 / 发布时间 / 撤单与截止时间：报名守卫同口径。 */
    private final TaskParticipationService taskParticipationService;
    /** 任务标题：详情展示用，禁 join task 表。 */
    private final TaskQueryService taskQueryService;
    /** 双方公开昵称；不含手机号、证件、真实姓名。 */
    private final IdentityProfileService identityProfileService;
    /** 账号状态与劳动者身份开通情况（FR-ACCT.STATUS.01）。 */
    private final AccountStatusQueryService accountStatusQueryService;
    /** 非当事人访问写 {@code audit_log}（bizType=CHAT）。 */
    private final AuditRecorder auditRecorder;

    /**
     * 开启或取回咨询会话（FR-MSG.CHAT.01，补遗 §2.1）。
     *
     * <p>仅「招募中」任务可开：已发布、未撤单、未过结束时间。劳动者发起时雇主取任务归属；
     * 雇主发起时校验目标账号可用且已开通劳动者身份。</p>
     *
     * @param current 会话主体
     * @param request 任务 ID + 可选目标劳动者
     * @return 会话视图；重复发起返回既有会话
     * @throws ApiException 401 未登录 / 400 参数缺失或 scope 非 INQUIRY /
     *                      404 任务或目标账号不存在 / 403 非招募中、自己对自己、非雇主劳动者身份
     */
    public ConversationView createInquiry(LoginUser current, CreateConversationRequest request) {
        Long userId = requireLogin(current);
        requirePartyIdentity(current);
        if (request == null || request.taskId() == null) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "taskId 不能为空");
        }
        if (request.scope() != null && request.scope() != ChatScope.INQUIRY) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "仅支持发起 INQUIRY 咨询会话");
        }

        TaskParticipationService.ParticipationFacts facts =
                taskParticipationService.factsOf(request.taskId());
        // 草稿对非雇主不可见：返回 404 而不是 403，避免泄露任务存在性
        if (facts.publishedAt() == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        if (facts.cancelledAt() != null || !LocalDateTime.now().isBefore(facts.endTime())) {
            throw new ApiException(ErrorCode.FORBIDDEN, TASK_NOT_RECRUITING_MESSAGE);
        }

        Long employerId;
        Long workerId;
        if (current.currentIdentity() == IdentityType.EMPLOYER) {
            employerId = userId;
            workerId = request.targetUserId();
            if (workerId == null) {
                throw new ApiException(ErrorCode.INVALID_PARAMETER, "雇主发起咨询须指定 targetUserId");
            }
            if (workerId.equals(userId)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "不能与自己发起会话");
            }
            // 目标账号不存在 / 被禁用 / 未开通劳动者身份：统一 404，不暴露账号状态
            if (!accountStatusQueryService.isActiveWorker(workerId)) {
                throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "目标账号不存在或不可用");
            }
        } else {
            employerId = facts.employerId();
            workerId = userId;
            if (employerId.equals(userId)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "不能与自己发起会话");
            }
        }

        ChatConversation candidate = new ChatConversation();
        candidate.setScope(ChatScope.INQUIRY);
        candidate.setTaskId(request.taskId());
        candidate.setBizId(INQUIRY_BIZ_ID);
        candidate.setEmployerId(employerId);
        candidate.setWorkerId(workerId);
        candidate.setInitiatorId(userId);
        candidate.setStatus(ChatConversationStatus.OPEN);
        ChatConversation saved = insertOrLoad(candidate);
        log.info("咨询会话就绪 conversationId={} taskId={} userId={}",
                saved.getId(), saved.getTaskId(), userId);
        return toView(saved, taskQueryService.briefOf(saved.getTaskId()));
    }

    /**
     * 报名成功后建 / 取 {@code APPLICATION} 会话（CARD-10 事件调用，幂等）。
     *
     * @param taskId        任务 ID
     * @param applicationId 报名 ID，作为 {@code biz_id}
     * @param employerId    雇主账号 ID
     * @param workerId      劳动者账号 ID
     * @return 会话 ID
     * @throws ApiException 400 归属参数不完整
     */
    public Long ensureApplication(Long taskId, Long applicationId, Long employerId, Long workerId) {
        return ensure(ChatScope.APPLICATION, taskId, applicationId, employerId, workerId);
    }

    /**
     * 录用 / 签约生效后建 / 取 {@code CONTRACT} 会话（CARD-10 事件调用，幂等）。
     *
     * <p>1 任务 N 名额 → N 个签约 → N 个会话，靠 {@code biz_id} 互不串。</p>
     *
     * @param taskId     任务 ID
     * @param contractId 签约 ID，作为 {@code biz_id}
     * @param employerId 雇主账号 ID
     * @param workerId   劳动者账号 ID
     * @return 会话 ID
     * @throws ApiException 400 归属参数不完整
     */
    public Long ensureContract(Long taskId, Long contractId, Long employerId, Long workerId) {
        return ensure(ChatScope.CONTRACT, taskId, contractId, employerId, workerId);
    }

    /**
     * 我的会话列表，按当前身份过滤（补遗 §5.2，FR-MSG.CHAT.01）。
     *
     * @param current 会话主体
     * @param scope   可选范围过滤，{@code null} 为全部
     * @param page    页码，从 1 开始
     * @param size    每页条数，服务层夹到 1~50
     * @return 会话分页视图
     * @throws ApiException 401 未登录 / 403 当前身份非雇主或劳动者
     */
    public PageView<ConversationListItemView> list(LoginUser current, ChatScope scope, int page, int size) {
        Long userId = requireLogin(current);
        requirePartyIdentity(current);
        int pageNo = Math.max(1, page);
        int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        boolean asEmployer = current.currentIdentity() == IdentityType.EMPLOYER;

        List<ChatConversation> rows = conversationMapper.selectPageByIdentity(
                userId, asEmployer, scope, (pageNo - 1) * pageSize, pageSize);
        long total = conversationMapper.countByIdentity(userId, asEmployer, scope);
        List<ConversationListItemView> items = rows.stream()
                // 未读数由 CARD-07 的 ChatUnreadService 填充，本卡先给 0
                .map(row -> new ConversationListItemView(row.getId(), row.getScope(), row.getTaskId(),
                        row.getStatus(), row.getLastMessageAt(), 0L))
                .toList();
        return PageView.of(items, pageNo, pageSize, total);
    }

    /**
     * 会话详情（补遗 §4 {@code GET /api/conversations/{id}}）。
     *
     * <p>非当事人 → 403 + {@code audit_log}（bizType=CHAT）。方法刻意不标 {@code readOnly=true}：
     * 审计是业务事务的一部分，只读事务会让插入失败。</p>
     *
     * @param current        会话主体
     * @param conversationId 会话 ID
     * @return 会话详情（任务标题 + 双方公开昵称）
     * @throws ApiException 401 未登录 / 404 会话不存在 / 403 非当事人
     */
    public ConversationView detail(LoginUser current, Long conversationId) {
        Long userId = requireLogin(current);
        ChatConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        if (!isParty(conversation, userId)) {
            log.warn("越权查看会话 conversationId={} userId={}", conversationId, userId);
            auditRecorder.record(AUDIT_BIZ_TYPE, String.valueOf(conversationId), AUDIT_ACTION_FORBIDDEN,
                    null, null, "userId=" + userId);
            throw new ApiException(ErrorCode.FORBIDDEN, FORBIDDEN_MESSAGE);
        }
        return toView(conversation, taskQueryService.briefOf(conversation.getTaskId()));
    }

    /**
     * 任务终态 → 该任务下全部会话归档（CARD-11 调用，幂等）。
     *
     * <p>唯一合法跃迁 {@code OPEN → ARCHIVED} 由 {@link ChatConversationStateMachine} 定义，
     * SQL 的 {@code WHERE status='OPEN'} 与之一致；重复触发返回 0 行，不报错。</p>
     *
     * @param taskId 任务 ID
     * @return 本次归档行数
     * @throws ApiException 400 taskId 为空
     */
    public int archiveByTask(Long taskId) {
        if (taskId == null) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "taskId 不能为空");
        }
        ChatConversationStateMachine.transit(ChatConversationStatus.OPEN,
                ChatConversationStateMachine.Event.ARCHIVE);
        int archived = conversationMapper.archiveByTaskId(taskId);
        if (archived > 0) {
            log.info("会话归档 taskId={} archived={}", taskId, archived);
        }
        return archived;
    }

    private Long ensure(ChatScope scope, Long taskId, Long bizId, Long employerId, Long workerId) {
        if (taskId == null || bizId == null || employerId == null || workerId == null) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "会话归属参数不完整");
        }
        ChatConversation candidate = new ChatConversation();
        candidate.setScope(scope);
        candidate.setTaskId(taskId);
        candidate.setBizId(bizId);
        candidate.setEmployerId(employerId);
        candidate.setWorkerId(workerId);
        candidate.setInitiatorId(SYSTEM_INITIATOR_ID);
        candidate.setStatus(ChatConversationStatus.OPEN);
        ChatConversation saved = insertOrLoad(candidate);
        log.info("会话就绪 scope={} bizId={} conversationId={}", scope, bizId, saved.getId());
        return saved.getId();
    }

    /**
     * 插入撞唯一键后回查既有会话（project_rules §6.2.8：禁止先 SELECT 再 INSERT）。
     */
    private ChatConversation insertOrLoad(ChatConversation candidate) {
        try {
            conversationMapper.insert(candidate);
            return candidate;
        } catch (DuplicateKeyException ex) {
            ChatConversation existing = conversationMapper.selectByUniqueKey(candidate.getScope(),
                    candidate.getTaskId(), candidate.getBizId(), candidate.getEmployerId(),
                    candidate.getWorkerId());
            if (existing == null) {
                // 撞键却查不到：不是本唯一键，按原异常抛出，不吞掉真实故障
                throw ex;
            }
            log.info("会话唯一键命中，返回既有 conversationId={} scope={}",
                    existing.getId(), existing.getScope());
            return existing;
        }
    }

    private ConversationView toView(ChatConversation conversation, TaskQueryService.TaskBrief brief) {
        Map<Long, String> nicknames = identityProfileService.nicknames(
                List.of(conversation.getEmployerId(), conversation.getWorkerId()));
        return new ConversationView(conversation.getId(), conversation.getScope(), conversation.getTaskId(),
                brief.title(), conversation.getEmployerId(), conversation.getWorkerId(),
                nicknames.getOrDefault(conversation.getEmployerId(), ""),
                nicknames.getOrDefault(conversation.getWorkerId(), ""),
                conversation.getStatus(), conversation.getLastMessageAt(), conversation.getCreatedAt());
    }

    private static boolean isParty(ChatConversation conversation, Long userId) {
        return userId.equals(conversation.getEmployerId()) || userId.equals(conversation.getWorkerId());
    }

    private static Long requireLogin(LoginUser current) {
        if (current == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_MESSAGE);
        }
        return current.userId();
    }

    /** 会话只对雇主 / 劳动者身份可见；管理员走 CARD-09 的只读取证入口。 */
    private static void requirePartyIdentity(LoginUser current) {
        IdentityType identity = current.currentIdentity();
        if (identity != IdentityType.EMPLOYER && identity != IdentityType.WORKER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅雇主或劳动者身份可访问会话");
        }
    }
}
