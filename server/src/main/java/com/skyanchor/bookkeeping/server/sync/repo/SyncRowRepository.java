package com.skyanchor.bookkeeping.server.sync.repo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户数据空间级联操作：注销账号时物理清除云端业务数据、变更日志与冲突日志
 * （基线第 32.3 章）。独立入口便于审计与测试。
 */
@Repository
public class SyncRowRepository {

    private final CategoryRowRepository categoryRepository;
    private final AccountRowRepository accountRepository;
    private final TransactionRowRepository transactionRepository;
    private final BudgetRowRepository budgetRepository;
    private final RecurringRowRepository recurringRepository;
    private final SyncChangeRepository changeRepository;
    private final ConflictLogRepository conflictRepository;

    @Autowired
    public SyncRowRepository(CategoryRowRepository categoryRepository,
                             AccountRowRepository accountRepository,
                             TransactionRowRepository transactionRepository,
                             BudgetRowRepository budgetRepository,
                             RecurringRowRepository recurringRepository,
                             SyncChangeRepository changeRepository,
                             ConflictLogRepository conflictRepository) {
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.recurringRepository = recurringRepository;
        this.changeRepository = changeRepository;
        this.conflictRepository = conflictRepository;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteAllUserData(Long userId) {
        categoryRepository.deleteAllByUserId(userId);
        accountRepository.deleteAllByUserId(userId);
        transactionRepository.deleteAllByUserId(userId);
        budgetRepository.deleteAllByUserId(userId);
        recurringRepository.deleteAllByUserId(userId);
        changeRepository.deleteAllByUserId(userId);
        conflictRepository.deleteAllByUserId(userId);
    }

    /** bootstrap/summary：云端各实体有效（未软删）行数。 */
    public Counts counts(Long userId) {
        return new Counts(
                categoryRepository.countByUserIdAndDeletedFalse(userId),
                accountRepository.countByUserIdAndDeletedFalse(userId),
                transactionRepository.countByUserIdAndDeletedFalse(userId),
                budgetRepository.countByUserIdAndDeletedFalse(userId),
                recurringRepository.countByUserIdAndDeletedFalse(userId));
    }

    /** 云端是否已有该账号的任何数据（含软删行）。 */
    public boolean hasAnyData(Long userId) {
        return categoryRepository.countByUserId(userId) > 0
                || accountRepository.countByUserId(userId) > 0
                || transactionRepository.countByUserId(userId) > 0
                || budgetRepository.countByUserId(userId) > 0
                || recurringRepository.countByUserId(userId) > 0;
    }

    public record Counts(long category, long account, long transaction,
                         long budget, long recurring) {
    }
}
