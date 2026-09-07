-- 随机访问令牌仅保存 SHA-256 摘要；已有会话保持 NULL，必须重新登录。
ALTER TABLE auth_session
    ADD COLUMN access_token_hash BINARY(32) NULL,
    ADD COLUMN access_expires_at DATETIME(3) NULL,
    ADD COLUMN active_device_key VARCHAR(86) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN revoked_at IS NULL THEN CONCAT(user_id, ':', device_id) ELSE NULL END) STORED,
    ADD CONSTRAINT uk_auth_access_token UNIQUE (access_token_hash),
    ADD CONSTRAINT uk_auth_active_device UNIQUE (active_device_key),
    ADD CONSTRAINT ck_auth_access_pair CHECK (
        (access_token_hash IS NULL AND access_expires_at IS NULL) OR
        (access_token_hash IS NOT NULL AND access_expires_at IS NOT NULL AND access_expires_at <= expires_at)
    );
