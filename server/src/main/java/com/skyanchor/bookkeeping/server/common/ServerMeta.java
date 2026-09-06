package com.skyanchor.bookkeeping.server.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 服务器级元数据（非业务数据）：recovery_epoch、最近备份时间等运行状态。 */
@Entity
@Table(name = "server_meta")
public class ServerMeta {

    public static final String KEY_RECOVERY_EPOCH = "recovery_epoch";
    public static final String KEY_LAST_BACKUP_AT = "last_backup_at";
    public static final String KEY_LAST_BACKUP_FILE = "last_backup_file";

    @Id
    @Column(name = "meta_key", length = 50)
    private String key;

    @Column(name = "meta_value", nullable = false)
    private String value;

    protected ServerMeta() {
    }

    public ServerMeta(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
