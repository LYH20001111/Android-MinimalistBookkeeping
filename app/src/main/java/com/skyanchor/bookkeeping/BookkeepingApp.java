package com.skyanchor.bookkeeping;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

import com.skyanchor.bookkeeping.data.database.AppDatabase;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.domain.importexport.BackupUseCase;
import com.skyanchor.bookkeeping.domain.importexport.ExportTransactionsUseCase;
import com.skyanchor.bookkeeping.domain.importexport.ImportTransactionsUseCase;
import com.skyanchor.bookkeeping.domain.importexport.RestoreUseCase;
import com.skyanchor.bookkeeping.domain.transaction.SearchTransactionsUseCase;
import com.skyanchor.bookkeeping.util.CategoryColors;
import com.skyanchor.bookkeeping.util.ThemeStore;

/**
 * 应用入口。负责在第一个 Activity 创建之前完成：主题应用、图表配色载入、数据库预热。
 *
 * <p>V1 本地优先，不申请任何网络权限，全部能力离线可用。
 */
public class BookkeepingApp extends Application {

    private BookkeepingRepository repository;

    /** 搜索用例单例：组合根统一持有，ViewModel 通过 {@link #get} 取得（V2 Phase 4）。 */
    private SearchTransactionsUseCase searchTransactionsUseCase;

    /** CSV 导出用例单例（V2 Phase 5）。 */
    private ExportTransactionsUseCase exportTransactionsUseCase;

    /** CSV 导入用例单例（V2 Phase 5）。 */
    private ImportTransactionsUseCase importTransactionsUseCase;

    /** 本地备份用例单例（V2 Phase 7）。 */
    private BackupUseCase backupUseCase;

    /** 本地恢复用例单例（V2 Phase 7）。 */
    private RestoreUseCase restoreUseCase;

    @NonNull
    public static BookkeepingApp get(@NonNull Context context) {
        return (BookkeepingApp) context.getApplicationContext();
    }

    @NonNull
    public BookkeepingRepository getRepository() {
        return repository;
    }

    @NonNull
    public SearchTransactionsUseCase getSearchTransactionsUseCase() {
        return searchTransactionsUseCase;
    }

    @NonNull
    public ExportTransactionsUseCase getExportTransactionsUseCase() {
        return exportTransactionsUseCase;
    }

    @NonNull
    public ImportTransactionsUseCase getImportTransactionsUseCase() {
        return importTransactionsUseCase;
    }

    @NonNull
    public BackupUseCase getBackupUseCase() {
        return backupUseCase;
    }

    @NonNull
    public RestoreUseCase getRestoreUseCase() {
        return restoreUseCase;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 先按缓存同步应用夜间模式，避免冷启动时明暗闪烁
        ThemeStore.apply(this);
        CategoryColors.init(this);
        repository = new BookkeepingRepository(this, AppDatabase.getInstance(this));
        searchTransactionsUseCase = new SearchTransactionsUseCase(repository);
        exportTransactionsUseCase = new ExportTransactionsUseCase(this, repository);
        importTransactionsUseCase = new ImportTransactionsUseCase(this, repository);
        backupUseCase = new BackupUseCase(this, repository);
        restoreUseCase = new RestoreUseCase(this, repository);
        // 触发建库 + 写入系统默认分类，保证首次进入记录页就能直接记账
        repository.warmUp();
        // 余额缓存一致性兜底：启动时全量「缓存 vs 重算」，偏差以重算纠正（V2 Phase 9）
        repository.validateAccountBalances(null);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 深浅色切换后重新载入图表配色 Token
        CategoryColors.init(this);
    }
}
