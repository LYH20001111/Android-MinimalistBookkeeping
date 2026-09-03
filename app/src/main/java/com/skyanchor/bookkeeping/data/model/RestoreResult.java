package com.skyanchor.bookkeeping.data.model;

/**
 * 本地恢复结果（V2 新增，开发计划 Phase 7）。
 *
 * <p>失败时 {@link #success} 为 false，{@link #reason} 为语义码（界面据此本地化文案）；
 * 恢复在单个 DB 事务内完成，任何失败都会整体回滚，当前数据不受影响。
 */
public final class RestoreResult {

    public static final int REASON_NONE = 0;
    /** 读取备份文件失败。 */
    public static final int REASON_IO = 1;
    /** 无法解析（不是有效 JSON）。 */
    public static final int REASON_MALFORMED = 2;
    /** 备份格式版本不受支持。 */
    public static final int REASON_VERSION = 3;
    /** 数据不完整或跨表引用失效（外键校验失败），事务已回滚。 */
    public static final int REASON_INVALID = 4;

    public final boolean success;

    /** 失败原因语义码；成功时为 {@link #REASON_NONE}。 */
    public final int reason;

    public final int accountCount;
    public final int categoryCount;
    public final int transactionCount;
    public final int budgetCount;
    public final int recurringCount;

    private RestoreResult(boolean success, int reason, int accountCount, int categoryCount,
                          int transactionCount, int budgetCount, int recurringCount) {
        this.success = success;
        this.reason = reason;
        this.accountCount = accountCount;
        this.categoryCount = categoryCount;
        this.transactionCount = transactionCount;
        this.budgetCount = budgetCount;
        this.recurringCount = recurringCount;
    }

    public static RestoreResult ok(int accountCount, int categoryCount, int transactionCount,
                                   int budgetCount, int recurringCount) {
        return new RestoreResult(true, REASON_NONE, accountCount, categoryCount,
                transactionCount, budgetCount, recurringCount);
    }

    public static RestoreResult failed(int reason) {
        return new RestoreResult(false, reason, 0, 0, 0, 0, 0);
    }
}
