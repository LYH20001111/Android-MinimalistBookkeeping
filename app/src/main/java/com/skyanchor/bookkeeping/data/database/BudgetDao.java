package com.skyanchor.bookkeeping.data.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.skyanchor.bookkeeping.data.entity.BudgetEntity;

/**
 * 预算 DAO。预算按 year + month 唯一，只以支出计算消耗。
 */
@Dao
public interface BudgetDao {

    @Query("SELECT * FROM budget WHERE year = :year AND month = :month")
    LiveData<BudgetEntity> observe(int year, int month);

    @Query("SELECT * FROM budget WHERE year = :year AND month = :month")
    BudgetEntity get(int year, int month);

    /**
     * 依赖 (year, month) 唯一索引做 upsert：已存在则替换，保留主键由调用方保证。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long upsert(BudgetEntity entity);

    @Query("DELETE FROM budget WHERE year = :year AND month = :month")
    void delete(int year, int month);

    @Query("DELETE FROM budget")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM budget")
    LiveData<Integer> observeCount();
}
