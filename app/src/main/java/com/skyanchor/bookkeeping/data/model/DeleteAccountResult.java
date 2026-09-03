package com.skyanchor.bookkeeping.data.model;

/**
 * 账户删除结果（V2 新增）。
 *
 * <p>与分类删除守卫同风格：账户被历史账单引用后禁止物理删除，只能归档。
 * 守卫放在 Repository 层，不依赖界面层自觉。
 */
public final class DeleteAccountResult {

    /** 是否删除成功。 */
    public final boolean success;

    /** 失败时该账户被多少笔账单引用（含转出 / 转入）。 */
    public final int usedCount;

    public DeleteAccountResult(boolean success, int usedCount) {
        this.success = success;
        this.usedCount = usedCount;
    }

    public static DeleteAccountResult ok() {
        return new DeleteAccountResult(true, 0);
    }

    public static DeleteAccountResult blocked(int usedCount) {
        return new DeleteAccountResult(false, usedCount);
    }
}
