package com.skyanchor.bookkeeping.domain.account;

import com.skyanchor.bookkeeping.data.database.AccountDao;
import com.skyanchor.bookkeeping.data.database.TransactionDao;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;

/**
 * 账户余额重算用例（V2 新增）。
 *
 * <p>余额统一「可正可负」模型：
 * {@code balance = initial_balance + 收入 - 支出 + 转入 - 转出}。
 * 信用卡欠款即负余额，不单独设计信用账务。
 *
 * <p>{@link #compute} 是与框架无关的纯函数，可在 JVM 单元测试中直接验证；
 * {@link #calculate} 从 DAO 取初始余额与四类交易合计后调用它，是余额的唯一真值来源，
 * 与 {@code AccountDao.observeAccountBalances()} 的 SQL 投影互为独立实现、彼此校验。
 */
public class CalculateAccountBalanceUseCase {

    private final AccountDao accountDao;
    private final TransactionDao transactionDao;

    public CalculateAccountBalanceUseCase(AccountDao accountDao, TransactionDao transactionDao) {
        this.accountDao = accountDao;
        this.transactionDao = transactionDao;
    }

    /**
     * 纯函数：由初始余额与四类交易合计计算账户余额（分），可正可负。
     *
     * @param initialBalance 初始余额
     * @param income         收入合计（type=2 且 account_id 命中）
     * @param expense        支出合计（type=1 且 account_id 命中）
     * @param transferIn     转入合计（type=3 且 transfer_account_id 命中）
     * @param transferOut    转出合计（type=3 且 account_id 命中）
     */
    public static long compute(long initialBalance, long income, long expense,
                               long transferIn, long transferOut) {
        return initialBalance + income - expense + transferIn - transferOut;
    }

    /**
     * 从交易重算指定账户的当前余额（分）。账户不存在时返回 0。
     */
    public long calculate(long accountId) {
        AccountEntity account = accountDao.getById(accountId);
        if (account == null) {
            return 0L;
        }
        long income = transactionDao.sumByTypeAndAccount(CategoryEntity.TYPE_INCOME, accountId);
        long expense = transactionDao.sumByTypeAndAccount(CategoryEntity.TYPE_EXPENSE, accountId);
        long transferIn = transactionDao.sumTransferIn(accountId);
        long transferOut = transactionDao.sumTransferOut(accountId);
        return compute(account.initialBalance, income, expense, transferIn, transferOut);
    }
}
