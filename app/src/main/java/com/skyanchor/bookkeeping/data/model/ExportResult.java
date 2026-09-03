package com.skyanchor.bookkeeping.data.model;

/**
 * CSV 导出结果（V2 新增，开发计划 Phase 5）。
 *
 * <p>{@link #success} 为 false 表示写文件失败（SAF Uri 不可写、IO 异常等），此时 {@link #count}
 * 为 0；成功时 {@link #count} 是实际写入的账单行数（不含表头）。UI 据此弹出成功 / 失败反馈。
 */
public final class ExportResult {

    public final boolean success;
    public final int count;

    private ExportResult(boolean success, int count) {
        this.success = success;
        this.count = count;
    }

    public static ExportResult ok(int count) {
        return new ExportResult(true, count);
    }

    public static ExportResult failed() {
        return new ExportResult(false, 0);
    }
}
