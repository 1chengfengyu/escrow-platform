-- V11：任务内沟通与私聊（FR-MSG.CHAT.01~03，补遗 v1 §3）
-- 只新增，不修改 V1~V22。金额/费率不涉及；不碰资金链路。
-- 三类会话共用一张表：INQUIRY 咨询 / APPLICATION 报名 / CONTRACT 签约。
-- 会话不依赖签约：INQUIRY 在报名前即可开启。
-- 消息只追加，未读用读游标表达，避免回写消息表。

CREATE TABLE chat_conversation
(
    id              BIGINT      NOT NULL COMMENT '雪花ID',
    scope           VARCHAR(16) NOT NULL COMMENT '会话范围：INQUIRY 咨询 / APPLICATION 报名 / CONTRACT 签约',
    task_id         BIGINT      NOT NULL COMMENT '任务ID（逻辑外键 task.id）',
    biz_id          BIGINT      NOT NULL DEFAULT 0 COMMENT '范围业务ID：APPLICATION=报名ID / CONTRACT=签约ID；INQUIRY 固定 0',
    employer_id     BIGINT      NOT NULL COMMENT '雇主账号ID（逻辑外键 platform_user.id）',
    worker_id       BIGINT      NOT NULL COMMENT '劳动者账号ID（逻辑外键 platform_user.id）',
    initiator_id    BIGINT      NOT NULL COMMENT '发起人账号ID，须为双方之一（应用层校验）',
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 进行中 / ARCHIVED 已归档只读',
    last_message_at DATETIME(3) NULL     COMMENT '最后消息时间，用于会话列表排序',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_conv_scope_task_biz_pair (scope, task_id, biz_id, employer_id, worker_id),
    KEY idx_chat_conv_employer (employer_id, last_message_at),
    KEY idx_chat_conv_worker (worker_id, last_message_at),
    KEY idx_chat_conv_task (task_id),
    CONSTRAINT ck_chat_conv_scope CHECK (scope IN ('INQUIRY', 'APPLICATION', 'CONTRACT')),
    CONSTRAINT ck_chat_conv_status CHECK (status IN ('OPEN', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务内会话（咨询/报名/签约）';

-- 只追加：无 updated_at / read_flag / deleted，编辑、撤回、删除在表结构上就无法表达。
CREATE TABLE chat_message
(
    id              BIGINT        NOT NULL COMMENT '雪花ID',
    conversation_id BIGINT        NOT NULL COMMENT '会话ID（逻辑外键 chat_conversation.id）',
    sender_id       BIGINT        NOT NULL COMMENT '发送人账号ID',
    msg_type        VARCHAR(12)   NOT NULL DEFAULT 'TEXT' COMMENT '消息类型：TEXT 文字 / SYSTEM 系统提示',
    content         VARCHAR(1000) NOT NULL COMMENT '消息正文',
    contact_hint    TINYINT       NOT NULL DEFAULT 0 COMMENT '疑似联系方式标记：0 否 / 1 是（只提示不拦截）',
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发送时间',
    PRIMARY KEY (id),
    KEY idx_chat_msg_conv_time (conversation_id, created_at, id),
    CONSTRAINT ck_chat_msg_type CHECK (msg_type IN ('TEXT', 'SYSTEM')),
    CONSTRAINT ck_chat_msg_hint CHECK (contact_hint IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话消息（只追加）';

-- 读游标：未读数 = id > last_read_message_id AND sender_id <> 当前用户。
CREATE TABLE chat_read_cursor
(
    conversation_id      BIGINT NOT NULL COMMENT '会话ID',
    user_id              BIGINT NOT NULL COMMENT '用户账号ID',
    last_read_message_id BIGINT NOT NULL DEFAULT 0 COMMENT '已读到的最后一条消息ID，0 表示未读任何消息',
    PRIMARY KEY (conversation_id, user_id),
    KEY idx_chat_cursor_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话读游标（未读数由它推导）';
