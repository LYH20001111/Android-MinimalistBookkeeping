package com.skyanchor.bookkeeping.data.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.skyanchor.bookkeeping.data.entity.BudgetEntity;

import java.util.List;

/**
 * 预算 DAO。
 *
 * <p>V1 只有「月总预算」，按 year + month 唯一。V2 唯一索引改为 (year, month, category_id)，
 * category_id = 0 是总预算哨兵。下面 {@link #observe(int, int)} / {@link #get(int, int)} /
 * {@link #delete(int, int)} 仍只针对总预算（category_id = 0），保持 V1 调用方语义不变；
 * 分类预算的读写见 Phase 6 新增方法。
 */
@Dao
public interface BudgetDao {

    /** 观察某月总预算（category_id = 0）。 */
    @Query("SELECT * FROM budget WHERE year = :year AND month = :month AND category_id = 0")
    LiveData<BudgetEntity> observe(int year, int month);

    /** 读取某月总预算（category_id = 0）。 */
    @Query("SELECT * FROM budget WHERE year = :year AND month = :month AND category_id = 0")
    BudgetEntity get(int year, int month);

    /** 观察某月总预算 + 全部分类预算。 */
    @Query("SELECT * FROM budget WHERE year = :year AND month = :month "
            + "ORDER BY category_id ASC")
    LiveData<List<BudgetEntity>> observeAllForMonth(int year, int month);

    /** 观察某月全部分类预算（category_id >= 1）。 */
    @Query("SELECT * FROM budget WHERE year = :year AND month = :month AND category_id > 0 "
            + "ORDER BY category_id ASC")
    LiveData<List<BudgetEntity>> observeCategoryBudgets(int year, int month);

    /** 观察某月某分类预算；categoryId = 0 时即总预算。 */
    @Query("SELECT * FROM budget WHERE year = :year AND month = :month AND category_id = :categoryId")
    LiveData<BudgetEntity> observe(int year, int month, int categoryId);

    /** 读取某月某分类预算；categoryId = 0 时即总预算。 */
    @Query("SELECT * FROM budget WHERE year = :year AND month = :month AND category_id = :categoryId")
    BudgetEntity get(int year, int month, int categoryId);

    /**
     * 依赖 (year, month, category_id) 唯一索引做 upsert：已存在则替换，主键由调用方保证。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long upsert(BudgetEntity entity);

    /** 删除某月总预算（category_id = 0）。 */
    @Query("DELETE FROM budget WHERE year = :year AND month = :month AND category_id = 0")
    void delete(int year, int month);

    /** 删除某月某分类预算。 */
    @Query("DELETE FROM budget WHERE year = :year AND month = :month AND category_id = :categoryId")
    void delete(int year, int month, int categoryId);

    @Query("DELETE FROM budget")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM budget")
    LiveData<Integer> observeCount();

    @Query("SELECT * FROM budget ORDER BY year ASC, month ASC, category_id ASC")
    List<BudgetEntity> getAll();
}
