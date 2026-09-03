package com.skyanchor.bookkeeping.data.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.model.AccountBalance;

import java.util.List;

/**
 * 账户 DAO（V2 新增）。读全部以 LiveData 暴露，写由 Repository 落到 IO 线程。
 *
 * <p>{@link #observeAccountBalances()} 用相关子查询从交易实时重算每个账户余额，
 * 是余额的唯一真值来源；{@code account.balance} 只是缓存列，写入交易时在同一事务内对齐。
 */
@Dao
public interface AccountDao {

    /** 余额重算投影：initial_balance + 收入 - 支出 + 转入 - 转出。 */
    String BALANCE_EXPR = "(a.initial_balance"
            + " + COALESCE((SELECT SUM(t.amount) FROM transactions t"
            + "   WHERE t.type = 2 AND t.account_id = a.id), 0)"
            + " - COALESCE((SELECT SUM(t.amount) FROM transactions t"
            + "   WHERE t.type = 1 AND t.account_id = a.id), 0)"
            + " + COALESCE((SELECT SUM(t.amount) FROM transactions t"
            + "   WHERE t.type = 3 AND t.transfer_account_id = a.id), 0)"
            + " - COALESCE((SELECT SUM(t.amount) FROM transactions t"
            + "   WHERE t.type = 3 AND t.account_id = a.id), 0))";

    /** 全部账户（含已归档），按 sort_order 升序，供账户管理页使用。 */
    @Query("SELECT * FROM account ORDER BY sort_order ASC, id ASC")
    LiveData<List<AccountEntity>> observeAll();

    /** 未归档账户，按 sort_order 升序，供记账 / 转账账户选择器使用。 */
    @Query("SELECT * FROM account WHERE is_archived = 0 ORDER BY sort_order ASC, id ASC")
    LiveData<List<AccountEntity>> observeActive();

    /** 全部账户余额（联表重算），按 sort_order 升序，供图表页「账户资金」卡片使用。 */
    @Query("SELECT a.id AS id, a.name AS name, a.type AS type, a.is_credit AS is_credit, "
            + "a.sort_order AS sort_order, a.is_archived AS is_archived, "
            + "a.initial_balance AS initial_balance, " + BALANCE_EXPR + " AS balance "
            + "FROM account a ORDER BY a.sort_order ASC, a.id ASC")
    LiveData<List<AccountBalance>> observeAccountBalances();

    /** 未归档账户余额（联表重算），用于总资产等只统计活跃账户的场景。 */
    @Query("SELECT a.id AS id, a.name AS name, a.type AS type, a.is_credit AS is_credit, "
            + "a.sort_order AS sort_order, a.is_archived AS is_archived, "
            + "a.initial_balance AS initial_balance, " + BALANCE_EXPR + " AS balance "
            + "FROM account a WHERE a.is_archived = 0 ORDER BY a.sort_order ASC, a.id ASC")
    LiveData<List<AccountBalance>> observeActiveAccountBalances();

    @Query("SELECT * FROM account WHERE id = :id")
    AccountEntity getById(long id);

    @Query("SELECT * FROM account ORDER BY sort_order ASC, id ASC")
    List<AccountEntity> getAll();

    /** 单个账户的重算余额（分），供一致性校验与缓存纠正使用。 */
    @Query("SELECT " + BALANCE_EXPR + " FROM account a WHERE a.id = :id")
    long recalcBalance(long id);

    /** 首个未归档账户 id，记账默认落账账户；无账户时返回 null。 */
    @Query("SELECT id FROM account WHERE is_archived = 0 ORDER BY sort_order ASC, id ASC LIMIT 1")
    Long firstActiveAccountId();

    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM account")
    int maxSortOrder();

    @Query("SELECT COUNT(*) FROM account")
    LiveData<Integer> observeCount();

    @Query("SELECT COUNT(*) FROM account")
    int count();

    @Insert
    long insert(AccountEntity entity);

    @Insert
    void insertAll(List<AccountEntity> entities);

    @Update
    void update(AccountEntity entity);

    @Update
    void updateAll(List<AccountEntity> entities);

    /** 仅更新余额缓存列，由 Repository 在写入交易的同一事务内调用。 */
    @Query("UPDATE account SET balance = :balance, updated_at = :updatedAt WHERE id = :id")
    void updateBalance(long id, long balance, long updatedAt);

    /** 归档 / 取消归档账户（不物理删除），被账单引用的账户只能归档。 */
    @Query("UPDATE account SET is_archived = :archived, updated_at = :updatedAt WHERE id = :id")
    void setArchived(long id, boolean archived, long updatedAt);

    /** 删除守卫：账户被账单（含转出 / 转入）引用的数量。 */
    @Query("SELECT COUNT(*) FROM transactions WHERE account_id = :id OR transfer_account_id = :id")
    int countTransactionsByAccount(long id);

    @Query("DELETE FROM account WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM account")
    void deleteAll();
}
