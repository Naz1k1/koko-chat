-- 过期记录作为幂等墓碑保留，禁止旧编号重新上传；已绑定消息的对象永不进入此清理流程。
ALTER TABLE attachment DROP CHECK ck_attachment_status,
    ADD CONSTRAINT ck_attachment_status CHECK ((status IN ('PENDING','READY','EXPIRED') AND message_id IS NULL) OR (status='ATTACHED' AND message_id IS NOT NULL)),
    ADD COLUMN expires_at DATETIME(3) NOT NULL DEFAULT (DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 1 DAY)) COMMENT '未发送附件有效期',
    ADD COLUMN cleanup_at DATETIME(3) NULL COMMENT '最近一次过期对象清理时间',
    ADD KEY idx_attachment_expiry (status, expires_at),
    ADD KEY idx_attachment_cleanup (status, cleanup_at);
