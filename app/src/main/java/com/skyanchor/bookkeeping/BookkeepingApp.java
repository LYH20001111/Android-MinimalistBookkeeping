package com.skyanchor.bookkeeping;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.database.AppDatabase;
import com.skyanchor.bookkeeping.data.remote.ApiClient;
import com.skyanchor.bookkeeping.data.remote.ServerConfigStore;
import com.skyanchor.bookkeeping.data.remote.TokenStore;
import com.skyanchor.bookkeeping.data.repository.AuthRepository;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.domain.importexport.BackupUseCase;
import com.skyanchor.bookkeeping.domain.importexport.ExportTransactionsUseCase;
import com.skyanchor.bookkeeping.domain.importexport.ImportTransactionsUseCase;
import com.skyanchor.bookkeeping.domain.importexport.RestoreUseCase;
import com.skyanchor.bookkeeping.domain.transaction.SearchTransactionsUseCase;
import com.skyanchor.bookkeeping.sync.SyncCoordinator;
import com.skyanchor.bookkeeping.sync.SyncEnqueuer;
import com.skyanchor.bookkeeping.sync.SyncScheduler;
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

    // ===== V3：认证与云同步组合根 =====

    private TokenStore tokenStore;
    private ServerConfigStore serverConfigStore;
    private ApiClient apiClient;
    private AuthRepository authRepository;
    private SyncEnqueuer syncEnqueuer;
    private SyncCoordinator syncCoordinator;

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

    @NonNull
    public TokenStore getTokenStore() {
        return tokenStore;
    }

    @NonNull
    public ServerConfigStore getServerConfigStore() {
        return serverConfigStore;
    }

    @NonNull
    public AuthRepository getAuthRepository() {
        return authRepository;
    }

    @NonNull
    public SyncCoordinator getSyncCoordinator() {
        return syncCoordinator;
    }

    @NonNull
    public ApiClient getApiClient() {
        return apiClient;
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
        // V3：认证与同步基础设施。本地优先不变——以下组件全部是「可选增强」，
        // 服务器关机 / 未登录 / 未配置时本地功能照常（基线第 1、2 章）。
        tokenStore = new TokenStore(this);
        serverConfigStore = new ServerConfigStore(this);
        apiClient = new ApiClient(tokenStore, serverConfigStore, () -> {
            // Refresh Token 失效：触发一轮同步让状态机转 AUTH_REQUIRED
            SyncScheduler.requestSyncNow();
        });
        authRepository = new AuthRepository(apiClient, tokenStore, serverConfigStore);
        syncEnqueuer = new SyncEnqueuer(AppDatabase.getInstance(this), repository.getIoExecutor());
        repository.setSyncEnqueuer(syncEnqueuer);
        syncCoordinator = new SyncCoordinator(AppDatabase.getInstance(this), repository,
                apiClient, tokenStore, syncEnqueuer);
        SyncScheduler.init(this, syncCoordinator, tokenStore, serverConfigStore);
        // 本地变更 → 3 秒防抖同步（基线 9.3）
        syncEnqueuer.setListener(() -> {
            SyncScheduler scheduler = SyncScheduler.get();
            if (scheduler != null) {
                scheduler.onLocalChangeEnqueued();
            }
        });
        // 前后台切换 → 同步（基线 9.2）
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacksAdapter());
        // App 启动触发一次同步（基线 9.1；未启用同步 / 未登录时为 no-op）
        repository.warmUp();
        // 余额缓存一致性兜底：启动时全量「缓存 vs 重算」，偏差以重算纠正（V2 Phase 9）
        repository.validateAccountBalances(null);
        SyncScheduler.get().onAppStarted();
    }

    /** Activity 生命周期计数（前台判定），只关心 start/stop。 */
    private static final class ActivityLifecycleCallbacksAdapter
            implements android.app.Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityStarted(@NonNull android.app.Activity activity) {
            SyncScheduler.get().onActivityStarted();
        }

        @Override
        public void onActivityStopped(@NonNull android.app.Activity activity) {
            SyncScheduler.get().onActivityStopped();
        }

        @Override
        public void onActivityCreated(@NonNull android.app.Activity activity,
                                      @Nullable android.os.Bundle savedInstanceState) {
        }

        @Override
        public void onActivityResumed(@NonNull android.app.Activity activity) {
        }

        @Override
        public void onActivityPaused(@NonNull android.app.Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull android.app.Activity activity,
                                                @NonNull android.os.Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull android.app.Activity activity) {
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 深浅色切换后重新载入图表配色 Token
        CategoryColors.init(this);
    }
}
