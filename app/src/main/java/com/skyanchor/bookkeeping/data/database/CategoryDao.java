package com.skyanchor.bookkeeping.data.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;

import java.util.List;

/**
 * 分类 DAO。支持新增、编辑、删除、排序，支出与收入分类分离（V1 基线第 6 章）。
 */
@Dao
public interface CategoryDao {

    @Query("SELECT * FROM category WHERE type = :type ORDER BY sort_order ASC, id ASC")
    LiveData<List<CategoryEntity>> observeByType(int type);

    @Query("SELECT * FROM category ORDER BY type ASC, sort_order ASC, id ASC")
    LiveData<List<CategoryEntity>> observeAll();

    @Query("SELECT * FROM category WHERE id = :id")
    CategoryEntity getById(long id);

    @Query("SELECT * FROM category WHERE type = :type ORDER BY sort_order ASC, id ASC")
    List<CategoryEntity> getByType(int type);

    @Insert
    long insert(CategoryEntity entity);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAll(List<CategoryEntity> entities);

    @Update
    void update(CategoryEntity entity);

    @Update
    void updateAll(List<CategoryEntity> entities);

    @Query("DELETE FROM category WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM category")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM category WHERE type = :type")
    int countByType(int type);

    @Query("SELECT COUNT(*) FROM category")
    LiveData<Integer> observeCount();

    /** 当前类型下最大的 sortOrder，用于新增分类时追加到末尾。 */
    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM category WHERE type = :type")
    int maxSortOrder(int type);
}
