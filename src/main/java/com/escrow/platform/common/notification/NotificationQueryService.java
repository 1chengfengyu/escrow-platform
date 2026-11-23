package com.escrow.platform.common.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.infrastructure.mapper.NotificationInboxMapper;
import com.escrow.platform.common.notification.api.NotificationView;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站内信查询与已读操作（V7，FR-MSG.LIST.01）。
 *
 * <p>所有读写都强制带 {@code user_id = 当前会话}：站内信没有「公共可见」语义，
 * 别人的消息一律按 404 处理，避免用 403 反推「这条消息存在」。</p>
 *
 * <p>已读是幂等操作：重复点已读不报错，条件更新影响 0 行也返回成功。</p>
 *
 * @since V7
 */
@Service
@RequiredArgsConstructor
public class NotificationQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int FLAG_UNREAD = 0;
    private static final int FLAG_READ = 1;
    private static final String UNAUTHENTICATED_MESSAGE = "请先登录";
    private static final String NOT_FOUND_MESSAGE = "站内信不存在";
    private static final String READ_FLAG_MESSAGE = "readFlag 只允许 0 / 1";

    private final NotificationInboxMapper notificationInboxMapper;

    /**
     * 分页查询当前用户的站内信（FR-MSG.LIST.01）。
     *
     * @param current  会话主体
     * @param readFlag 0 未读 / 1 已读 / {@code null} 全部
     * @param page     页码，从 1 开始
     * @param size     每页条数（1~{@value #MAX_PAGE_SIZE}）
     * @return 分页视图
     * @throws ApiException 401 未登录 / 400 readFlag 非法
     */
    @Transactional(readOnly = true)
    public PageView<NotificationView> list(LoginUser current, Integer readFlag, int page, int size) {
        Long userId = requireLogin(current);
        Integer filter = parseReadFlag(readFlag);
        int pageNo = Math.max(1, page);
        int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));

        LambdaQueryWrapper<NotificationInbox> wrapper = new LambdaQueryWrapper<NotificationInbox>()
                .eq(NotificationInbox::getUserId, userId)
                .orderByDesc(NotificationInbox::getCreatedAt)
                .orderByDesc(NotificationInbox::getId);
        if (filter != null) {
            wrapper.eq(NotificationInbox::getReadFlag, filter);
        }
        Page<NotificationInbox> result =
                notificationInboxMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return PageView.of(result.getRecords().stream()
                        .map(NotificationQueryService::toView).toList(),
                pageNo, pageSize, result.getTotal());
    }

    /**
     * 标记单条已读（FR-MSG.LIST.01）。
     *
     * @param current 会话主体
     * @param id      站内信 ID
     * @throws ApiException 401 未登录 / 404 不存在或不属于当前用户
     */
    @Transactional
    public void markRead(LoginUser current, Long id) {
        Long userId = requireLogin(current);
        NotificationInbox inbox = notificationInboxMapper.selectById(id);
        if (inbox == null || !userId.equals(inbox.getUserId())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        // 条件更新：read_flag=0 才写，重复点已读影响 0 行也算成功（幂等）
        notificationInboxMapper.update(null, new LambdaUpdateWrapper<NotificationInbox>()
                .eq(NotificationInbox::getId, id)
                .eq(NotificationInbox::getUserId, userId)
                .eq(NotificationInbox::getReadFlag, FLAG_UNREAD)
                .set(NotificationInbox::getReadFlag, FLAG_READ));
    }

    /**
     * 当前用户全部标记已读（FR-MSG.LIST.01）。
     *
     * @param current 会话主体
     * @return 实际标记条数
     * @throws ApiException 401 未登录
     */
    @Transactional
    public int markAllRead(LoginUser current) {
        Long userId = requireLogin(current);
        return notificationInboxMapper.update(null, new LambdaUpdateWrapper<NotificationInbox>()
                .eq(NotificationInbox::getUserId, userId)
                .eq(NotificationInbox::getReadFlag, FLAG_UNREAD)
                .set(NotificationInbox::getReadFlag, FLAG_READ));
    }

    /**
     * 未读条数（FR-MSG.LIST.01）。
     *
     * @param current 会话主体
     * @return 未读条数
     * @throws ApiException 401 未登录
     */
    @Transactional(readOnly = true)
    public long unreadCount(LoginUser current) {
        Long userId = requireLogin(current);
        Long count = notificationInboxMapper.selectCount(new LambdaQueryWrapper<NotificationInbox>()
                .eq(NotificationInbox::getUserId, userId)
                .eq(NotificationInbox::getReadFlag, FLAG_UNREAD));
        return count == null ? 0L : count;
    }

    private static Long requireLogin(LoginUser current) {
        if (current == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_MESSAGE);
        }
        return current.userId();
    }

    private static Integer parseReadFlag(Integer readFlag) {
        if (readFlag == null) {
            return null;
        }
        if (readFlag != FLAG_UNREAD && readFlag != FLAG_READ) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, READ_FLAG_MESSAGE);
        }
        return readFlag;
    }

    private static NotificationView toView(NotificationInbox inbox) {
        return new NotificationView(inbox.getId(), inbox.getBizType(), inbox.getBizNo(),
                inbox.getTitle(), inbox.getContent(),
                inbox.getReadFlag() != null && inbox.getReadFlag() == FLAG_READ,
                inbox.getCreatedAt());
    }
}
