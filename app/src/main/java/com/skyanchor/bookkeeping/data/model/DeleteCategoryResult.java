package com.skyanchor.bookkeeping.data.model;

/**
 * 分类删除结果。V1 基线第 6 章：禁止直接删除「已被使用」的分类，避免统计数据断裂。
 */
public final class DeleteCategoryResult {

    /** 是否删除成功。 */
    public final boolean success;

    /** 失败时该分类被多少笔账单引用。 */
    public final int usedCount;

    public DeleteCategoryResult(boolean success, int usedCount) {
        this.success = success;
        this.usedCount = usedCount;
    }

    public static DeleteCategoryResult ok() {
        return new DeleteCategoryResult(true, 0);
    }

    public static DeleteCategoryResult blocked(int usedCount) {
        return new DeleteCategoryResult(false, usedCount);
    }
}
