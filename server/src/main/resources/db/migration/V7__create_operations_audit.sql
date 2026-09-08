-- 运维死信归档与审计；不关联用户外键，保留故障证据但查询接口不返回正文。
CREATE TABLE ops_dead_letter (
    id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '队列命名空间与原始正文的SHA256',
    scope VARCHAR(48) NOT NULL COMMENT 'MQ命名空间',
    body MEDIUMBLOB NOT NULL COMMENT '原始事件，仅内部重放使用',
    first_seen DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_seen DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    occurrences BIGINT NOT NULL DEFAULT 1 COMMENT '含确认丢失重投，不等于独立故障数',
    reviewed_at DATETIME(3) NULL COMMENT '已确认处理到的时间',
    KEY idx_ops_dead_scope (scope,id), KEY idx_ops_dead_unreviewed (scope,reviewed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='死信持久归档';
CREATE TABLE ops_action (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '操作请求幂等编号',
    dead_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(12) NOT NULL COMMENT 'REPLAY重放或ACK确认归档',
    actor VARCHAR(64) NOT NULL COMMENT '服务配置的运维凭据标识',
    reason VARCHAR(200) NOT NULL COMMENT '操作原因',
    payload TEXT NULL COMMENT '校验后重建的消息事件',
    status VARCHAR(16) NOT NULL COMMENT '待发布、持有租约、已发布或已确认',
    active_dead_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (IF(status IN ('PENDING','PUBLISHING'),dead_id,NULL)) STORED,
    attempts INT NOT NULL DEFAULT 0,
    lease_token CHAR(36) NULL, lease_until DATETIME(3) NULL,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    last_error VARCHAR(64) NULL COMMENT '错误类型，不记录凭据或请求',
    UNIQUE KEY uk_ops_active_replay (active_dead_id),
    KEY idx_ops_action_claim (status,next_attempt_at),
    KEY idx_ops_action_dead (dead_id,created_at),
    CONSTRAINT fk_ops_action_dead FOREIGN KEY(dead_id) REFERENCES ops_dead_letter(id),
    CONSTRAINT ck_ops_action_kind CHECK(kind IN ('REPLAY','ACK')),
    CONSTRAINT ck_ops_action_status CHECK(status IN ('PENDING','PUBLISHING','PUBLISHED','ACKNOWLEDGED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维操作审计与重放发布租约';
