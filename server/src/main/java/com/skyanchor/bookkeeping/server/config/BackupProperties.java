package com.skyanchor.bookkeeping.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务器备份配置（V3.1 基线第 12/13 章）。
 * 默认每天 03:00 自动备份；保留策略：最近 7 天每天一份 + 最近 4 周每周一份
 * + 最近 12 个月每月一份；磁盘剩余不足 1GB 时跳过自动备份。
 */
@ConfigurationProperties(prefix = "app.backup")
public class BackupProperties {

    /** 备份文件目录（相对服务器工作目录或绝对路径）。 */
    private String dir = "./backups";
    /** 每日自动备份 cron（Spring 6 任务调度格式，含秒位）。 */
    private String dailyCron = "0 0 3 * * *";
    /** 是否启用每日自动备份。 */
    private boolean autoEnabled = true;
    /** 自动备份所需的最小剩余磁盘空间（字节），不足则跳过。 */
    private long minFreeBytes = 1024L * 1024L * 1024L;

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public String getDailyCron() {
        return dailyCron;
    }

    public void setDailyCron(String dailyCron) {
        this.dailyCron = dailyCron;
    }

    public boolean isAutoEnabled() {
        return autoEnabled;
    }

    public void setAutoEnabled(boolean autoEnabled) {
        this.autoEnabled = autoEnabled;
    }

    public long getMinFreeBytes() {
        return minFreeBytes;
    }

    public void setMinFreeBytes(long minFreeBytes) {
        this.minFreeBytes = minFreeBytes;
    }
}
