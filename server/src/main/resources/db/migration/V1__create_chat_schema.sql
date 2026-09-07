-- koko-chat 首期业务表，适用于 MySQL 8.4 / InnoDB。
-- 由 Flyway 在已选定的数据库中执行：不包含 CREATE DATABASE、USE 或破坏性重建。
-- 业务 ID 使用应用分配的正数 BIGINT，对应 Java Long；网络传输统一使用字符串。
-- DATETIME(3) 保存 UTC 时间，应用连接须设置 UTC；本迁移不写入默认账号或业务样例。
-- 外键使用默认 RESTRICT 语义，禁止级联删除聊天历史。

CREATE TABLE app_user (
    id BIGINT NOT NULL COMMENT '用户 ID，由应用分配',
    account VARCHAR(64) NOT NULL COMMENT '登录账号，不区分大小写和重音；注册时由应用规范化',
    password_hash VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '带算法标识及参数的密码哈希，不保存明文密码',
    nickname VARCHAR(64) NOT NULL COMMENT '用户显示昵称',
    avatar_url VARCHAR(512) NULL COMMENT '头像资源引用，可为空',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '账号状态：ACTIVE、DISABLED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '资料更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_account (account),
    CONSTRAINT ck_app_user_id CHECK (id > 0),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号与用户资料';

CREATE TABLE auth_session (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '登录会话 UUID，与一次 WebSocket 连接标识区分',
    user_id BIGINT NOT NULL COMMENT '登录用户 ID',
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '设备稳定标识，按用户隔离',
    refresh_token_hash BINARY(32) NOT NULL COMMENT '随机刷新凭证的 SHA-256 原始摘要，不保存原始凭证',
    expires_at DATETIME(3) NOT NULL COMMENT '登录会话过期时间，UTC',
    revoked_at DATETIME(3) NULL COMMENT '注销或替换时记录撤销时间，NULL 表示尚未显式撤销',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '登录时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_session_refresh_hash (refresh_token_hash),
    KEY idx_auth_session_device (user_id, device_id, revoked_at),
    KEY idx_auth_session_expiry (expires_at, id),
    CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_auth_session_expiry CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录会话及刷新凭证摘要；设备替换由应用事务协调';

CREATE TABLE friend_request (
    id BIGINT NOT NULL COMMENT '好友申请 ID，由应用分配',
    sender_id BIGINT NOT NULL COMMENT '申请发起人',
    receiver_id BIGINT NOT NULL COMMENT '申请接收人',
    greeting VARCHAR(255) NULL COMMENT '申请附言',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING' COMMENT '申请状态：PENDING、ACCEPTED、REJECTED、CANCELLED',
    pending_pair VARCHAR(41) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN status = 'PENDING'
            THEN CONCAT(LEAST(sender_id, receiver_id), ':', GREATEST(sender_id, receiver_id))
            ELSE NULL END) STORED COMMENT '待处理申请的无序用户对，已处理记录为 NULL，可重新申请',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '申请时间，UTC',
    handled_at DATETIME(3) NULL COMMENT '处理时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_friend_request_pending_pair (pending_pair),
    KEY idx_friend_request_inbox (receiver_id, status, created_at, id),
    KEY idx_friend_request_outbox (sender_id, status, created_at, id),
    CONSTRAINT fk_friend_request_sender FOREIGN KEY (sender_id) REFERENCES app_user (id),
    CONSTRAINT fk_friend_request_receiver FOREIGN KEY (receiver_id) REFERENCES app_user (id),
    CONSTRAINT ck_friend_request_id CHECK (id > 0),
    CONSTRAINT ck_friend_request_distinct CHECK (sender_id <> receiver_id),
    CONSTRAINT ck_friend_request_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_friend_request_handled CHECK (
        (status = 'PENDING' AND handled_at IS NULL) OR (status <> 'PENDING' AND handled_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='好友申请历史；同一对用户最多一条待处理申请';

CREATE TABLE friendship (
    user_id BIGINT NOT NULL COMMENT '好友关系所属用户',
    friend_id BIGINT NOT NULL COMMENT '好友用户 ID',
    remark VARCHAR(64) NULL COMMENT '当前用户设置的好友备注',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '建立好友关系的时间，UTC',
    PRIMARY KEY (user_id, friend_id),
    KEY idx_friendship_reverse (friend_id, user_id),
    CONSTRAINT fk_friendship_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_friendship_friend FOREIGN KEY (friend_id) REFERENCES app_user (id),
    CONSTRAINT ck_friendship_distinct CHECK (user_id <> friend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='双向好友关系；两条方向记录必须由应用在同一事务维护';

CREATE TABLE conversation (
    id BIGINT NOT NULL COMMENT '会话 ID，由应用分配',
    type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会话类型：DIRECT 单聊、GROUP 群聊',
    direct_key VARCHAR(41) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '单聊双方用户 ID 数值排序后以冒号连接；群聊为 NULL',
    title VARCHAR(128) NULL COMMENT '群名称；单聊不持久化对端昵称',
    owner_id BIGINT NULL COMMENT '群主 ID；单聊为 NULL',
    created_by BIGINT NOT NULL COMMENT '会话创建人',
    latest_seq BIGINT NOT NULL DEFAULT 0 COMMENT '已提交的最大消息序号；在会话行锁内与消息同事务递增',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态：ACTIVE、CLOSED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_direct (direct_key),
    KEY idx_conversation_owner (owner_id, id),
    KEY idx_conversation_creator (created_by, id),
    CONSTRAINT fk_conversation_owner FOREIGN KEY (owner_id) REFERENCES app_user (id),
    CONSTRAINT fk_conversation_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT ck_conversation_id CHECK (id > 0),
    CONSTRAINT ck_conversation_seq CHECK (latest_seq >= 0),
    CONSTRAINT ck_conversation_status CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT ck_conversation_shape CHECK (
        (type = 'DIRECT' AND direct_key IS NOT NULL AND owner_id IS NULL AND title IS NULL)
        OR (type = 'GROUP' AND direct_key IS NULL AND owner_id IS NOT NULL AND title IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一单聊与群聊；消息顺序以会话内 seq 为准';

CREATE TABLE conversation_member (
    conversation_id BIGINT NOT NULL COMMENT '所属会话',
    user_id BIGINT NOT NULL COMMENT '成员用户',
    role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MEMBER' COMMENT '角色：OWNER、ADMIN、MEMBER；单聊双方均为 MEMBER',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '成员状态：ACTIVE、LEFT、REMOVED',
    membership_epoch CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本次加入周期 UUID；重新加入必须更新',
    join_seq BIGINT NOT NULL COMMENT '本周期第一条可见消息序号，加入时为 latest_seq + 1',
    last_read_seq BIGINT NOT NULL COMMENT '用户已读进度，加入时为 join_seq - 1；更新必须单调',
    joined_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本次加入时间，UTC',
    left_at DATETIME(3) NULL COMMENT '本次退出或被移除时间，UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '成员关系最后修改时间，UTC',
    PRIMARY KEY (conversation_id, user_id),
    KEY idx_conversation_member_user (user_id, status, conversation_id),
    KEY idx_conversation_member_fanout (conversation_id, status, user_id),
    CONSTRAINT fk_conversation_member_conversation FOREIGN KEY (conversation_id) REFERENCES conversation (id),
    CONSTRAINT fk_conversation_member_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_conversation_member_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    CONSTRAINT ck_conversation_member_status CHECK (status IN ('ACTIVE', 'LEFT', 'REMOVED')),
    CONSTRAINT ck_conversation_member_cursor CHECK (join_seq > 0 AND last_read_seq >= join_seq - 1),
    CONSTRAINT ck_conversation_member_left CHECK (
        (status = 'ACTIVE' AND left_at IS NULL) OR (status <> 'ACTIVE' AND left_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每用户每会话的当前成员关系；权限和成员周期由事务维护';

CREATE TABLE message (
    id BIGINT NOT NULL COMMENT '服务端消息 ID，由应用分配',
    conversation_id BIGINT NOT NULL COMMENT '消息所属会话',
    seq BIGINT NOT NULL COMMENT '会话内连续消息序号，必须与 latest_seq 同事务分配',
    sender_id BIGINT NOT NULL COMMENT '从认证会话取得的发送者，不信任客户端自报身份',
    sender_membership_epoch CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '接受消息时发送者的成员周期，退出重入后仍保留原值',
    client_msg_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端稳定发送意图 ID，在发送者范围内唯一',
    type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '正文类型：TEXT、EMOJI',
    body JSON NOT NULL COMMENT '正文 JSON 对象；文本及完整消息字节上限由协议层校验',
    body_hash BINARY(32) NOT NULL COMMENT '规范化正文的 SHA-256 原始摘要，用于检查同一幂等 ID 内容冲突',
    server_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '服务端保存时间，UTC；不代替 seq 排序',
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_sender_client (sender_id, client_msg_id),
    UNIQUE KEY uk_message_conversation_seq (conversation_id, seq),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation (id),
    CONSTRAINT fk_message_sender FOREIGN KEY (sender_id) REFERENCES app_user (id),
    CONSTRAINT ck_message_id CHECK (id > 0),
    CONSTRAINT ck_message_seq CHECK (seq > 0),
    CONSTRAINT ck_message_type CHECK (type IN ('TEXT', 'EMOJI')),
    CONSTRAINT ck_message_body CHECK (JSON_TYPE(body) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='在线与离线消息的统一事实源；首期不物理删除';

CREATE TABLE device_cursor (
    user_id BIGINT NOT NULL COMMENT '接收设备所属用户',
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用户范围内的设备稳定标识',
    conversation_id BIGINT NOT NULL COMMENT '消息所属会话',
    membership_epoch CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '接收者本次成员周期，不能复用其他周期的进度',
    received_seq BIGINT NOT NULL COMMENT '设备已连续持久化到本地的最大序号镜像，不代表用户已读',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '回执更新时间，UTC',
    PRIMARY KEY (user_id, device_id, conversation_id, membership_epoch),
    KEY idx_device_cursor_conversation (conversation_id, user_id),
    CONSTRAINT fk_device_cursor_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_device_cursor_conversation FOREIGN KEY (conversation_id) REFERENCES conversation (id),
    CONSTRAINT ck_device_cursor_seq CHECK (received_seq >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备连续接收进度镜像；清空本地缓存时不能据此跳过历史重建';

CREATE TABLE message_outbox (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定业务事件 UUID，重复发布沿用该 ID',
    message_id BIGINT NOT NULL COMMENT '已提交的消息引用，与本行在同一事务写入',
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'message.created' COMMENT '业务事件类型',
    event_version SMALLINT NOT NULL DEFAULT 1 COMMENT '事件协议版本',
    payload JSON NOT NULL COMMENT '业务事件 JSON 对象，不包含凭证或 Channel 数据',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING' COMMENT '发布状态：PENDING、PUBLISHING、PUBLISHED',
    attempts INT NOT NULL DEFAULT 0 COMMENT 'Outbox 发布尝试次数，与 MQ 消费重试 attempt 分开',
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次允许发布的时间，UTC',
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '本次认领令牌；更新结果时按此值判断认领是否仍有效',
    lease_until DATETIME(3) NULL COMMENT '认领租约到期时间，UTC',
    published_at DATETIME(3) NULL COMMENT 'MQ confirm 成功且没有 mandatory return 后记录的时间，UTC',
    last_error VARCHAR(1024) NULL COMMENT '截断后的错误摘要，不记录原始消息凭证',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事件写入时间，UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '发布状态更新时间，UTC',
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_message_outbox_event (message_id, event_type),
    KEY idx_message_outbox_pending (status, next_attempt_at, event_id),
    KEY idx_message_outbox_lease (status, lease_until, event_id),
    KEY idx_message_outbox_cleanup (status, published_at, event_id),
    CONSTRAINT fk_message_outbox_message FOREIGN KEY (message_id) REFERENCES message (id),
    CONSTRAINT ck_message_outbox_status CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED')),
    CONSTRAINT ck_message_outbox_counters CHECK (event_version > 0 AND attempts >= 0),
    CONSTRAINT ck_message_outbox_payload CHECK (JSON_TYPE(payload) = 'OBJECT'),
    CONSTRAINT ck_message_outbox_lease CHECK (
        (status = 'PUBLISHING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PUBLISHING' AND lease_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_message_outbox_published CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL) OR (status <> 'PUBLISHED' AND published_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息事务与 RabbitMQ 发布之间的可靠通知记录';
