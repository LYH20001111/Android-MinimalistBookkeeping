package com.skyanchor.bookkeeping.data.model;

/**
 * CSV 导入提交结果（V2 新增，开发计划 Phase 5）。
 *
 * <p>批量插入在单个 DB 事务内完成，要么全部成功要么整体回滚，因此 {@link #inserted} 等于
 * 预览时的可导入行数。{@link #success} 为 false 表示事务失败（如中途外键约束异常），
 * 此时不会有任何行落库，UI 提示重试。「跳过 / 错误」计数由 {@link ImportPreview} 提供，
 * 与本结果一起组成最终的「成功 / 跳过 / 错误」报告。
 */
public final class ImportCommitResult {

    public final boolean success;
    public final int inserted;

    private ImportCommitResult(boolean success, int inserted) {
        this.success = success;
        this.inserted = inserted;
    }

    public static ImportCommitResult ok(int inserted) {
        return new ImportCommitResult(true, inserted);
    }

    public static ImportCommitResult failed() {
        return new ImportCommitResult(false, 0);
    }
}
