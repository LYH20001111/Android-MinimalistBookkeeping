package com.skyanchor.bookkeeping.domain.importexport;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.database.DefaultData;
import com.skyanchor.bookkeeping.data.model.BackupData;
import com.skyanchor.bookkeeping.data.model.RestoreResult;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.util.Callback;
import com.skyanchor.bookkeeping.util.ThemeStore;

import org.json.JSONException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 本地恢复用例（V2 新增，开发计划 Phase 7）。
 *
 * <p>仅做覆盖恢复，不做智能合并 / 冲突解决 / 增量：从 SAF {@code Uri} 读取备份 JSON →
 * 校验格式版本 → 在仓库的单事务内清空各表并按原 id 重插 → 重算全部账户余额。
 * 任何一步失败都整体回滚，当前数据不受影响；「恢复前二次确认覆盖」由界面层负责。
 */
public class RestoreUseCase {

    private final BookkeepingRepository repository;
    private final ContentResolver resolver;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public RestoreUseCase(@NonNull Context context, @NonNull BookkeepingRepository repository) {
        this.appContext = context.getApplicationContext();
        this.resolver = appContext.getContentResolver();
        this.repository = repository;
    }

    /** 从 {@code uri} 恢复全部数据，主线程回调恢复结果（含各类写入行数或失败原因）。 */
    public void restore(@NonNull Uri uri, @Nullable Callback<RestoreResult> callback) {
        repository.runOnIo(() -> post(callback, restore(uri)));
    }

    @NonNull
    private RestoreResult restore(@NonNull Uri uri) {
        String json = read(uri);
        if (json == null) {
            return RestoreResult.failed(RestoreResult.REASON_IO);
        }
        BackupData data;
        try {
            data = BackupSerializer.fromJson(json);
        } catch (JSONException | RuntimeException e) {
            return RestoreResult.failed(RestoreResult.REASON_MALFORMED);
        }
        if (data.schemaVersion != BackupSerializer.SCHEMA_VERSION) {
            return RestoreResult.failed(RestoreResult.REASON_VERSION);
        }
        // 备份文件缺设置段时回落到默认设置，保证恢复后 user_settings 表始终有单例行
        if (data.settings == null) {
            data.settings = DefaultData.defaultSettings(System.currentTimeMillis());
        }

        try {
            repository.replaceAllData(data.accounts, data.categories, data.transactions,
                    data.budgets, data.recurring, data.settings);
        } catch (RuntimeException e) {
            // 事务已回滚；引用失效（外键校验失败）等按数据非法处理
            return RestoreResult.failed(RestoreResult.REASON_INVALID);
        }

        // 主题镜像缓存与恢复后的设置保持一致；夜间模式由界面层按需立即应用
        ThemeStore.put(appContext, data.settings.theme);
        return RestoreResult.ok(data.accounts.size(), data.categories.size(),
                data.transactions.size(), data.budgets.size(), data.recurring.size());
    }

    /** 读取全部文本；SAF Uri 不可读或 IO 异常时返回 null。 */
    @Nullable
    private String read(@NonNull Uri uri) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resolver.openInputStream(uri), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
            }
            return builder.toString();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private <T> void post(@Nullable Callback<T> callback, T value) {
        if (callback == null) {
            return;
        }
        mainHandler.post(() -> callback.onResult(value));
    }
}
