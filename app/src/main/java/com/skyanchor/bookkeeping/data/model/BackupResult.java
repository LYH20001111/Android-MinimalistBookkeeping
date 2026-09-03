package com.skyanchor.bookkeeping.data.model;

/**
 * 本地备份结果（V2 新增，开发计划 Phase 7）。
 *
 * <p>{@link #success} 为 false 表示写文件失败（SAF Uri 不可写、IO 异常、序列化异常等），
 * 此时 {@link #transactionCount} 为 0；成功时为备份包含的账单数。
 */
public final class BackupResult {

    public final boolean success;

    /** 备份包含的账单数；失败时为 0。 */
    public final int transactionCount;

    private BackupResult(boolean success, int transactionCount) {
        this.success = success;
        this.transactionCount = transactionCount;
    }

    public static BackupResult ok(int transactionCount) {
        return new BackupResult(true, transactionCount);
    }

    public static BackupResult failed() {
        return new BackupResult(false, 0);
    }
}
