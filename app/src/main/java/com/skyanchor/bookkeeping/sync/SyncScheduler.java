package com.skyanchor.bookkeeping.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.remote.ServerConfigStore;
import com.skyanchor.bookkeeping.data.remote.TokenStore;

import java.util.concurrent.atomic.AtomicInteger;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * 同步调度器（基线第 9、28 章）：统一接管全部触发源，
 * 真值与队列仍在 Room，WorkManager 只负责可靠的延迟重试与进程死亡兜底。
 *
 * <ul>
 *   <li>进入 App / 回到前台：ActivityLifecycleCallbacks 计数 0→1 触发；</li>
 *   <li>本地变更：3 秒防抖（连续操作不断重置计时）；</li>
 *   <li>网络恢复：ConnectivityManager 回调，立即同步、绕过退避（基线 9.5/10.2）；</li>
 *   <li>失败重试：指数退避延迟的持久化 OneShot Work，进程被杀也能续上（基线 10.4）。</li>
 * </ul>
 */
public final class SyncScheduler {

    private static final long DEBOUNCE_MS = 3_000;
    /**
     * 前台轻量轮询间隔（基线第 9 章的补充触发源，见开发计划完成备注 13）：
     * 多设备场景下另一台设备的写入没有推送通道，前台每 30 秒主动拉取一次增量，
     * 保证「另一台手机记了账，这台很快就看得到」。仅前台运行，代价极小。
     */
    private static final long FOREGROUND_POLL_MS = 30_000;

    private static volatile SyncScheduler instance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger startedActivities = new AtomicInteger();

    private SyncCoordinator coordinator;
    private TokenStore tokenStore;
    private ServerConfigStore serverConfigStore;
    private boolean networkCallbackRegistered;
    private boolean networkAvailable = true;

    private final Runnable debounceRunnable = () -> {
        if (coordinator != null) {
            coordinator.requestSync(false);
        }
    };

    /** 前台轮询循环：仅在仍有可见 Activity 时自我续期。 */
    private final Runnable foregroundPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (startedActivities.get() > 0 && coordinator != null) {
                coordinator.requestSync(false);
                mainHandler.postDelayed(this, FOREGROUND_POLL_MS);
            }
        }
    };

    private SyncScheduler(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static SyncScheduler init(@NonNull Context context,
                                     @NonNull SyncCoordinator coordinator,
                                     @NonNull TokenStore tokenStore,
                                     @NonNull ServerConfigStore serverConfigStore) {
        SyncScheduler scheduler = new SyncScheduler(context);
        scheduler.coordinator = coordinator;
        scheduler.tokenStore = tokenStore;
        scheduler.serverConfigStore = serverConfigStore;
        instance = scheduler;
        scheduler.registerNetworkCallback();
        // 后台兜底：每 15 分钟周期同步（有网才执行），App 被杀/在后台也能追上其他设备的变更
        androidx.work.PeriodicWorkRequest periodic =
                new androidx.work.PeriodicWorkRequest.Builder(SyncWorker.class, 15, TimeUnit.MINUTES)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build())
                        .build();
        WorkManager.getInstance(scheduler.appContext).enqueueUniquePeriodicWork(
                "bookkeeping_sync_periodic", androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                periodic);
        return scheduler;
    }

    @Nullable
    public static SyncScheduler get() {
        return instance;
    }

    // ===== 触发源 =====

    /** App 启动（基线 9.1）：稍等建库完成后触发一次。 */
    public void onAppStarted() {
        mainHandler.postDelayed(() -> requestSyncNow(), 1_500);
    }

    /** 由 Application 的 ActivityLifecycleCallbacks 回调：计数 0→1 即回到前台（基线 9.2）。 */
    public void onActivityStarted() {
        if (startedActivities.incrementAndGet() == 1) {
            requestSyncNow();
            // 进入前台：启动 30 秒轻量轮询，多设备变更近实时收敛
            mainHandler.removeCallbacks(foregroundPollRunnable);
            mainHandler.postDelayed(foregroundPollRunnable, FOREGROUND_POLL_MS);
        }
    }

    public void onActivityStopped() {
        if (startedActivities.decrementAndGet() == 0) {
            // 全部页面离开前台：停止轮询（后台交给 WorkManager 周期任务）
            mainHandler.removeCallbacks(foregroundPollRunnable);
        }
    }

    /** 本地变更入队后回调（基线 9.3）：3 秒防抖。 */
    public void onLocalChangeEnqueued() {
        mainHandler.removeCallbacks(debounceRunnable);
        mainHandler.postDelayed(debounceRunnable, DEBOUNCE_MS);
    }

    /** 手动 / 网络恢复 / 服务器恢复：立即触发，不受退避限制（基线 9.4/9.5/10.3）。 */
    public static void requestSyncNow() {
        SyncScheduler scheduler = instance;
        if (scheduler != null && scheduler.coordinator != null) {
            scheduler.coordinator.requestSync(true);
        }
    }

    /** 失败后按指数退避调度持久化重试（基线 10.1）；网络恢复回调会提前触发。 */
    public static void scheduleRetry(long delayMs) {
        SyncScheduler scheduler = instance;
        if (scheduler == null) {
            return;
        }
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(scheduler.appContext).enqueueUniqueWork(
                "bookkeeping_sync_retry", ExistingWorkPolicy.REPLACE, request);
    }

    // ===== 网络监听（基线 9.5：网络恢复立即同步） =====

    private void registerNetworkCallback() {
        android.net.ConnectivityManager connectivityManager =
                (android.net.ConnectivityManager) appContext
                        .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null || networkCallbackRegistered) {
            return;
        }
        android.net.NetworkRequest request = new android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        networkCallbackRegistered = true;
        connectivityManager.registerNetworkCallback(request,
                new android.net.ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(@NonNull android.net.Network network) {
                        boolean wasOffline = !networkAvailable;
                        networkAvailable = true;
                        if (wasOffline) {
                            // 网络恢复：立即同步，不等待退避倒计时（基线 10.2）
                            requestSyncNow();
                        }
                    }

                    @Override
                    public void onLost(@NonNull android.net.Network network) {
                        networkAvailable = false;
                    }
                });
    }
}
