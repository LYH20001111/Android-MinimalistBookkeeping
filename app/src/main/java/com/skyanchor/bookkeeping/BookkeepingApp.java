package com.skyanchor.bookkeeping;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

import com.skyanchor.bookkeeping.data.database.AppDatabase;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.util.CategoryColors;
import com.skyanchor.bookkeeping.util.ThemeStore;

/**
 * 应用入口。负责在第一个 Activity 创建之前完成：主题应用、图表配色载入、数据库预热。
 *
 * <p>V1 本地优先，不申请任何网络权限，全部能力离线可用。
 */
public class BookkeepingApp extends Application {

    private BookkeepingRepository repository;

    @NonNull
    public static BookkeepingApp get(@NonNull Context context) {
        return (BookkeepingApp) context.getApplicationContext();
    }

    @NonNull
    public BookkeepingRepository getRepository() {
        return repository;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 先按缓存同步应用夜间模式，避免冷启动时明暗闪烁
        ThemeStore.apply(this);
        CategoryColors.init(this);
        repository = new BookkeepingRepository(this, AppDatabase.getInstance(this));
        // 触发建库 + 写入系统默认分类，保证首次进入记录页就能直接记账
        repository.warmUp();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 深浅色切换后重新载入图表配色 Token
        CategoryColors.init(this);
    }
}
