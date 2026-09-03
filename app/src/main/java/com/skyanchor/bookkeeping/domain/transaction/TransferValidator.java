package com.skyanchor.bookkeeping.domain.transaction;

/**
 * 转账校验（V2 新增，基线第 5 章转账）。
 *
 * <p>纯函数、无状态：转出账户与转入账户都必须已选（非 0）且互不相同，才是一笔合法转账。
 * 校验规则集中在此，供记一笔页保存前调用，也便于单元测试直接覆盖，不依赖 Android 环境。
 */
public final class TransferValidator {

    private TransferValidator() {
    }

    /**
     * 是否为合法转账：两账户都已选且不同。
     *
     * @param fromAccountId 转出账户 id，0 表示未选
     * @param toAccountId   转入账户 id，0 表示未选
     */
    public static boolean isValid(long fromAccountId, long toAccountId) {
        return fromAccountId != 0L && toAccountId != 0L && fromAccountId != toAccountId;
    }

    /**
     * 是否「两账户都已选但撞成同一个」：用于把提示从「请选择账户」精确成「不能转到同一账户」。
     */
    public static boolean isSameAccount(long fromAccountId, long toAccountId) {
        return fromAccountId != 0L && fromAccountId == toAccountId;
    }
}
