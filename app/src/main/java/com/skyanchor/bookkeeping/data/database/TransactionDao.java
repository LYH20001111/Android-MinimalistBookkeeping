package com.skyanchor.bookkeeping.data.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.data.model.DailySummary;
import com.skyanchor.bookkeeping.data.model.DayCount;

import java.util.List;

/**
 * 交易记录 DAO。除写入外，全部以 LiveData 暴露，保证「删除交易后所有相关统计立即重新计算」。
 */
@Dao
public interface TransactionDao {

    /** 联表投影列，供多个查询复用。 */
    String ITEM_COLUMNS = "t.id AS id, t.type AS type, t.amount AS amount, t.date AS date, "
            + "t.time AS time, t.note AS note, t.category_id AS categoryId, "
            + "c.name AS categoryName, c.icon AS categoryIcon";

    String ITEM_FROM = " FROM transactions t LEFT JOIN category c ON t.category_id = c.id ";

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

    @Query("SELECT " + ITEM_COLUMNS + ITEM_FROM + "WHERE t.id = :id")
    TransactionItem getById(long id);

    /** 取完整实体，用于更新时保留 created_at 等界面不展示的字段。 */
    @Query("SELECT * FROM transactions WHERE id = :id")
    TransactionEntity getEntityById(long id);

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
     * 观察每天账单笔数（全量），由 Java 侧聚合为周/月/年周期选项。
     * 单次查询，避免为每个周期分别查库（V1.1 基线第 35 章性能要求）。
     */
    @Query("SELECT date AS day, COUNT(*) AS transactionCount "
            + "FROM transactions GROUP BY date ORDER BY date ASC")
    LiveData<List<DayCount>> observeDayCounts();
}
