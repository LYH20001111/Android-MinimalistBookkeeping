-- V2：transactions 增补客户端业务创建时间（Pull 载荷回传，多设备保持原始创建事实）。
ALTER TABLE transactions
    ADD COLUMN client_created_at BIGINT NOT NULL DEFAULT 0;
