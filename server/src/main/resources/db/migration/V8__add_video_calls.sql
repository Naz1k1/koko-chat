-- 旧通话默认语音；媒体内容不入库，摄像头开关用于对端占位提示。
ALTER TABLE call_session
    ADD COLUMN media_type VARCHAR(8) NOT NULL DEFAULT 'AUDIO' COMMENT 'AUDIO/VIDEO，创建后不可改变',
    ADD COLUMN caller_camera BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN callee_camera BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_call_media CHECK (media_type IN ('AUDIO','VIDEO'));
ALTER TABLE call_session COMMENT='一对一音视频通话状态';
