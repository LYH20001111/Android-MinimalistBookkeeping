package com.skyanchor.bookkeeping.domain.account;

import androidx.annotation.NonNull;

import com.skyanchor.bookkeeping.data.database.AccountDao;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;

import java.util.List;

/**
 * 账户余额缓存一致性校验（V2 新增，开发计划 Phase 9）。
 *
 * <p>{@code account.balance} 是缓存列，正常情况下由仓库层在写入交易的同一事务内
 * 用 {@link CalculateAccountBalanceUseCase} 对齐；校验器是开发期兜底——
 * 全量比对「缓存 vs 重算」，不一致时以重算（唯一真值来源）纠正缓存。
 * 历史账单 {@code account_id = NULL} 不归属任何账户，不参与任何账户余额。
 */
public class AccountBalanceValidator {

    private final AccountDao accountDao;
    private final CalculateAccountBalanceUseCase balanceUseCase;

    public AccountBalanceValidator(@NonNull AccountDao accountDao,
                                   @NonNull CalculateAccountBalanceUseCase balanceUseCase) {
        this.accountDao = accountDao;
        this.balanceUseCase = balanceUseCase;
    }

    /**
     * 校验并纠正全部账户的余额缓存。
     *
     * @return 被纠正的账户数；0 表示缓存与重算全部一致。仅在 IO 线程调用。
     */
    public int validateAndFixAll() {
        List<AccountEntity> accounts = accountDao.getAll();
        int fixed = 0;
        long now = System.currentTimeMillis();
        for (AccountEntity account : accounts) {
            long expected = balanceUseCase.calculate(account.id);
            if (account.balance != expected) {
                accountDao.updateBalance(account.id, expected, now);
                fixed++;
            }
        }
        return fixed;
    }
}
