-- 通话状态持久化，媒体不进入数据库；信令仅在当前通话内短期保留。
CREATE TABLE call_session (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '客户端呼叫幂等编号',
    conversation_id BIGINT NOT NULL,
    caller_id BIGINT NOT NULL,
    callee_id BIGINT NOT NULL,
    caller_session CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    callee_session CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '首个接听设备会话',
    state VARCHAR(16) NOT NULL DEFAULT 'RINGING',
    reason VARCHAR(24) NULL,
    caller_connected BOOLEAN NOT NULL DEFAULT FALSE,
    callee_connected BOOLEAN NOT NULL DEFAULT FALSE,
    caller_seen DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    callee_seen DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NOT NULL,
    ended_at DATETIME(3) NULL,
    KEY idx_call_caller (caller_id,state), KEY idx_call_callee (callee_id,state), KEY idx_call_expiry (state,expires_at),
    CONSTRAINT fk_call_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    CONSTRAINT fk_call_caller FOREIGN KEY (caller_id) REFERENCES app_user(id),
    CONSTRAINT fk_call_callee FOREIGN KEY (callee_id) REFERENCES app_user(id),
    CONSTRAINT ck_call_state CHECK (state IN ('RINGING','CONNECTING','ACTIVE','ENDED')),
    CONSTRAINT ck_call_peers CHECK (caller_id<>callee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一对一语音通话记录';
CREATE TABLE call_signal (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    call_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sender_session CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    signal_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(16) NOT NULL COMMENT 'OFFER/ANSWER/ICE',
    payload TEXT NOT NULL COMMENT '短期协商信息，结束时删除，不记业务日志',
    UNIQUE KEY uk_call_signal (call_id,sender_session,signal_id),
    KEY idx_call_signal (call_id,id),
    CONSTRAINT fk_signal_call FOREIGN KEY (call_id) REFERENCES call_session(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通话期间可补拉的有限信令';
