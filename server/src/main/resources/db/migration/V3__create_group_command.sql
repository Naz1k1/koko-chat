-- 群管理命令的事务去重记录；响应丢失后重复调用不会重新建群或重复邀请。
CREATE TABLE group_command (
    user_id BIGINT NOT NULL COMMENT '命令发起人，来自认证身份',
    client_command_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端稳定操作编号',
    request_hash BINARY(32) NOT NULL COMMENT '操作类型、目标和规范化参数的 SHA-256 摘要',
    conversation_id BIGINT NOT NULL COMMENT '操作作用的群会话 ID，也是重复请求的返回结果',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '操作提交时间，UTC',
    PRIMARY KEY (user_id, client_command_id),
    KEY idx_group_command_conversation (conversation_id),
    CONSTRAINT fk_group_command_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_group_command_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='群操作去重记录；与建群或成员修改同事务提交';
