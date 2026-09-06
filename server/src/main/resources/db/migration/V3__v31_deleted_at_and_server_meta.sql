-- V3.1（私有云稳定性与服务器管理）：
-- 1) 业务表补 deleted_at：软删发生时间（epoch millis），回收站按它排序与展示，
--    并随同步载荷传播，保证多设备回收站一致；恢复（restore）时置回 NULL。
-- 2) server_meta 键值表：存放 recovery_epoch（服务器恢复代际）与最近备份信息。
--    恢复备份时 epoch +1，客户端据此识别“服务器已恢复”并重置游标重新收敛。

ALTER TABLE categories ADD COLUMN deleted_at BIGINT;
ALTER TABLE accounts ADD COLUMN deleted_at BIGINT;
ALTER TABLE transactions ADD COLUMN deleted_at BIGINT;
ALTER TABLE budgets ADD COLUMN deleted_at BIGINT;
ALTER TABLE recurring_transactions ADD COLUMN deleted_at BIGINT;

CREATE TABLE server_meta (
    meta_key   VARCHAR(50)  PRIMARY KEY,
    meta_value VARCHAR(255) NOT NULL
);

INSERT INTO server_meta (meta_key, meta_value) VALUES ('recovery_epoch', '0');
