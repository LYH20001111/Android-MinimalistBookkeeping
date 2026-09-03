package com.skyanchor.bookkeeping.data.database;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionExport;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.data.model.DailySummary;
import com.skyanchor.bookkeeping.data.model.DayCount;

import java.util.List;

/**
 * 交易记录 DAO。除写入外，全部以 LiveData 暴露，保证「删除交易后所有相关统计立即重新计算」。
 */
@Dao
public interface TransactionDao {

    /** 联表投影列，供多个查询复用。转账 category_id 为 NULL，用 COALESCE 归 0。 */
    String ITEM_COLUMNS = "t.id AS id, t.type AS type, t.amount AS amount, t.date AS date, "
            + "t.time AS time, t.note AS note, COALESCE(t.category_id, 0) AS categoryId, "
            + "c.name AS categoryName, c.icon AS categoryIcon, "
            + "t.account_id AS accountId, a.name AS accountName, "
            + "t.transfer_account_id AS transferAccountId, ta.name AS transferAccountName";

    String ITEM_FROM = " FROM transactions t "
            + "LEFT JOIN category c ON t.category_id = c.id "
            + "LEFT JOIN account a ON t.account_id = a.id "
            + "LEFT JOIN account ta ON t.transfer_account_id = ta.id ";

    /** 观察 [startDay, endDay] 区间内的账单，按业务日期倒序。 */
    @Query("SELECT " + ITEM_COLUMNS + ITEM_FROM
            + "WHERE t.date BETWEEN :startDay AND :endDay "
            + "ORDER BY t.date DESC, t.time DESC, t.id DESC")
    LiveData<List<TransactionItem>> observeBetween(long startDay, long endDay);

    /** 观察业务日期不晚于 endDay 的全部账单，用于记录页的历史账单列表。 */
    @Query("SELECT " + ITEM_COLUMNS + ITEM_FROM
            + "WHERE t.date <= :endDay "
            + "ORDER BY t.date DESC, t.time DESC, t.id DESC")
    LiveData<List<TransactionItem>> observeUpTo(long endDay);

    /**
     * 搜索 / 筛选账单（V2 新增，开发计划 Phase 4）。
     *
     * <p>可选参数模式：每个条件都用哨兵值表示「不限制」，因此一套 SQL 覆盖所有筛选组合——
     * <ul>
     *   <li>{@code :keyword IS NULL OR ...}：关键词命中备注 / 分类名 / 账户名 / 转入账户名；</li>
     *   <li>{@code :categoryId = 0 OR ...} / {@code :accountId = 0 OR ...}：0 表示不限；
     *       账户命中转出或转入任一端，故转账两端都算「涉及该账户」；</li>
     *   <li>{@code (:includeExpense AND type=1) OR ...}：类型集合，三者全 true 即不限；</li>
     *   <li>{@code BETWEEN}：日期与金额均为闭区间，默认 [0, MAX] / [0, MAX] 即不限。</li>
     * </ul>
     *
     * <p>排序与 {@link #observeUpTo} 一致（date DESC, time DESC, id DESC），
     * 结果可直接喂给 {@code StatisticsCalculator.groupByDay} 与 {@code TransactionListAdapter}。
     */
    @Query("SELECT " + ITEM_COLUMNS + ITEM_FROM
            + "WHERE t.date BETWEEN :startDay AND :endDay "
            + "AND t.amount BETWEEN :minAmount AND :maxAmount "
            + "AND (:categoryId = 0 OR t.category_id = :categoryId) "
            + "AND (:accountId = 0 OR t.account_id = :accountId "
            + "     OR t.transfer_account_id = :accountId) "
            + "AND ((:includeExpense AND t.type = 1) OR (:includeIncome AND t.type = 2) "
            + "     OR (:includeTransfer AND t.type = 3)) "
            + "AND (:keyword IS NULL OR t.note LIKE '%' || :keyword || '%' "
            + "     OR c.name LIKE '%' || :keyword || '%' "
            + "     OR a.name LIKE '%' || :keyword || '%' "
            + "     OR ta.name LIKE '%' || :keyword || '%') "
            + "ORDER BY t.date DESC, t.time DESC, t.id DESC")
    LiveData<List<TransactionItem>> search(@Nullable String keyword, long startDay, long endDay,
                                           boolean includeExpense, boolean includeIncome,
                                           boolean includeTransfer, long categoryId, long accountId,
                                           long minAmount, long maxAmount);

    @Query("SELECT " + ITEM_COLUMNS + ITEM_FROM + "WHERE t.id = :id")
    TransactionItem getById(long id);

    /**
     * 观察某账户的全部流水（V2 Phase 9）：转出与转入任一端命中即算（转账只出一行），
     * 排序与记录页一致，结果可直接交给 {@code StatisticsCalculator.groupByDay}。
     */
    @Query("SELECT " + ITEM_COLUMNS + ITEM_FROM
            + "WHERE t.account_id = :accountId OR t.transfer_account_id = :accountId "
            + "ORDER BY t.date DESC, t.time DESC, t.id DESC")
    LiveData<List<TransactionItem>> observeForAccount(long accountId);

    /** 取完整实体，用于更新时保留 created_at 等界面不展示的字段。 */
    @Query("SELECT * FROM transactions WHERE id = :id")
    TransactionEntity getEntityById(long id);

    // ------------------------------------------------------------------
    // V2 新增：CSV 导出 / 导入（开发计划 Phase 5），均为一次性同步查询，不进 LiveData
    // ------------------------------------------------------------------

    /**
     * 导出投影列：在列表投影基础上补 {@code created_at / updated_at} 两个时间戳，
     * 供 {@code CsvFormatter} 输出「创建时间 / 更新时间」列。
     */
    String EXPORT_COLUMNS = "t.id AS id, t.type AS type, t.amount AS amount, "
            + "COALESCE(t.category_id, 0) AS categoryId, c.name AS categoryName, "
            + "t.account_id AS accountId, a.name AS accountName, "
            + "t.transfer_account_id AS transferAccountId, ta.name AS transferAccountName, "
            + "t.date AS date, t.time AS time, t.note AS note, "
            + "t.created_at AS createdAt, t.updated_at AS updatedAt";

    /**
     * 全量导出，按业务日期 / 时间升序（自然账本顺序，便于人工核对与再次导入）。
     * 一次性同步查询，由 {@code ExportTransactionsUseCase} 在 IO 线程调用。
     */
    @Query("SELECT " + EXPORT_COLUMNS + ITEM_FROM + "ORDER BY t.date ASC, t.time ASC, t.id ASC")
    List<TransactionExport> exportAll();

    /** 全量实体，供导入时构建「疑似重复」指纹集合（同日期+时间+金额+分类+账户+备注）。 */
    @Query("SELECT * FROM transactions")
    List<TransactionEntity> getAllEntities();

    @Insert
    long insert(TransactionEntity entity);

    @Update
    void update(TransactionEntity entity);

    @Query("DELETE FROM transactions WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM transactions")
    void deleteAll();

    /** 区间内某一类型的金额合计（单位：分）。 */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions "
            + "WHERE type = :type AND date BETWEEN :startDay AND :endDay")
    LiveData<Long> observeSum(int type, long startDay, long endDay);

    /** 某个分类下的账单数量，用于分类删除守卫。 */
    @Query("SELECT COUNT(*) FROM transactions WHERE category_id = :categoryId")
    int countByCategory(long categoryId);

    // ------------------------------------------------------------------
    // V2 新增：账户余额重算所需的分类聚合（CalculateAccountBalanceUseCase 使用）
    // ------------------------------------------------------------------

    /** 某账户下指定类型（1=支出 / 2=收入）的金额合计（分）。 */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions "
            + "WHERE type = :type AND account_id = :accountId")
    long sumByTypeAndAccount(int type, long accountId);

    /** 转入某账户的转账合计（分）：type=3 且 transfer_account_id 命中。 */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions "
            + "WHERE type = 3 AND transfer_account_id = :accountId")
    long sumTransferIn(long accountId);

    /** 从某账户转出的转账合计（分）：type=3 且 account_id 命中。 */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions "
            + "WHERE type = 3 AND account_id = :accountId")
    long sumTransferOut(long accountId);

    /** 某账户相关的账单数量（含转出 / 转入），用于账户流水页与删除守卫交叉核对。 */
    @Query("SELECT COUNT(*) FROM transactions WHERE account_id = :accountId "
            + "OR transfer_account_id = :accountId")
    int countByAccount(long accountId);

    @Query("SELECT COUNT(*) FROM transactions")
    LiveData<Integer> observeCount();

    @Query("SELECT COUNT(*) FROM transactions")
    int count();

    // ------------------------------------------------------------------
    // V1.1 新增：日历摘要与周期选择器聚合查询
    // ------------------------------------------------------------------

    /**
     * 观察 [startDay, endDay] 区间内每天的收支摘要，用于日历选择器显示每日流水。
     * 只返回有账单的日期，无流水日期不出现在结果中（V1.1 基线第 6.2 节）。
     */
    @Query("SELECT date AS day, "
            + "COALESCE(SUM(CASE WHEN type = 1 THEN amount ELSE 0 END), 0) AS expense, "
            + "COALESCE(SUM(CASE WHEN type = 2 THEN amount ELSE 0 END), 0) AS income, "
            + "COUNT(*) AS transactionCount "
            + "FROM transactions "
            + "WHERE date BETWEEN :startDay AND :endDay "
            + "GROUP BY date ORDER BY date ASC")
    LiveData<List<DailySummary>> observeDailySummaries(long startDay, long endDay);

    /**
     * 观察按自然周聚合的账单笔数（V2 Risk C：有界聚合，一行 = 一个周）。
     *
     * <p>以 {@code strftime('%W')}（周一为一周第一天）分组，{@code day} 取该周最早一笔
     * 的日期，Java 侧再用 {@code DateUtil.startOfWeek(day)} 还原到周一。跨年周会被
     * {@code %Y-%W} 拆成两行，但两行的周一相同，Java 侧聚合时自然合并。
     */
    @Query("SELECT MIN(date) AS day, COUNT(*) AS transactionCount "
            + "FROM transactions "
            + "GROUP BY strftime('%Y-%W', date / 1000, 'unixepoch', 'localtime') "
            + "ORDER BY day ASC")
    LiveData<List<DayCount>> observeWeekCounts();

    /**
     * 观察按自然月聚合的账单笔数（V2 Risk C：一行 = 一个月）。
     * {@code day} 取该月最早一笔的日期，Java 侧据此还原年月。
     */
    @Query("SELECT MIN(date) AS day, COUNT(*) AS transactionCount "
            + "FROM transactions "
            + "GROUP BY strftime('%Y-%m', date / 1000, 'unixepoch', 'localtime') "
            + "ORDER BY day ASC")
    LiveData<List<DayCount>> observeMonthCounts();

    /**
     * 观察按自然年聚合的账单笔数（V2 Risk C：一行 = 一年）。
     * {@code day} 取该年最早一笔的日期，Java 侧据此还原年份。
     */
    @Query("SELECT MIN(date) AS day, COUNT(*) AS transactionCount "
            + "FROM transactions "
            + "GROUP BY strftime('%Y', date / 1000, 'unixepoch', 'localtime') "
            + "ORDER BY day ASC")
    LiveData<List<DayCount>> observeYearCounts();
}
