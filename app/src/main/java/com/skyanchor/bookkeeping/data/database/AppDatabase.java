package com.skyanchor.bookkeeping.data.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.entity.UserSettingsEntity;

/**
 * 本地数据库。V1 只保留 4 张核心表：transactions、category、budget、user_settings。
 */
@Database(
        entities = {
                TransactionEntity.class,
                CategoryEntity.class,
                BudgetEntity.class,
                UserSettingsEntity.class
        },
        version = 1,
        exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static final String DB_NAME = "bookkeeping.db";

    private static volatile AppDatabase instance;

    public abstract TransactionDao transactionDao();

    public abstract CategoryDao categoryDao();

    public abstract BudgetDao budgetDao();

    public abstract UserSettingsDao userSettingsDao();

    public static AppDatabase getInstance(@NonNull Context context) {
        AppDatabase local = instance;
        if (local == null) {
            synchronized (AppDatabase.class) {
                local = instance;
                if (local == null) {
                    local = Room.databaseBuilder(
                                    context.getApplicationContext(), AppDatabase.class, DB_NAME)
                            .addCallback(SEED_CALLBACK)
                            .build();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * 建库时同步写入系统默认分类与本地设置单例。
     *
     * <p>这里直接使用 {@link SupportSQLiteDatabase} 而不是 DAO，保证默认数据在任何查询返回之前
     * 就已落库，也避免在开库回调里再次获取数据库造成死锁。
     */
    private static final RoomDatabase.Callback SEED_CALLBACK = new RoomDatabase.Callback() {
        @Override
        public void onCreate(@NonNull SupportSQLiteDatabase db) {
            super.onCreate(db);
            for (CategoryEntity category : DefaultData.defaultCategories()) {
                ContentValues values = new ContentValues();
                values.put("name", category.name);
                values.put("icon", category.icon);
                values.put("type", category.type);
                values.put("sort_order", category.sortOrder);
                values.put("is_default", category.isDefault ? 1 : 0);
                db.insert("category", SQLiteDatabase.CONFLICT_IGNORE, values);
            }

            long now = System.currentTimeMillis();
            ContentValues settings = new ContentValues();
            settings.put("id", UserSettingsEntity.SINGLETON_ID);
            settings.put("theme", UserSettingsEntity.THEME_LIGHT);
            settings.put("first_launch", 1);
            settings.put("created_at", now);
            settings.put("updated_at", now);
            db.insert("user_settings", SQLiteDatabase.CONFLICT_REPLACE, settings);
        }
    };
}
