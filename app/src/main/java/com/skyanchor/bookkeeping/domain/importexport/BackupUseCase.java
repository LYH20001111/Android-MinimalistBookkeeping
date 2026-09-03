package com.skyanchor.bookkeeping.domain.importexport;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.model.BackupData;
import com.skyanchor.bookkeeping.data.model.BackupResult;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.util.Callback;

import org.json.JSONException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 本地备份用例（V2 新增，开发计划 Phase 7）。
 *
 * <p>在仓库的单线程 IO 上同步读取全部本地数据（账户 / 分类 / 交易 / 预算 / 周期账单 / 设置），
 * 经 {@link BackupSerializer} 序列化为版本化 JSON，写入用户经 SAF 选定的 {@link Uri}。
 * 读写全程在 IO 线程，结果回主线程回调。不申请存储权限、不联网。
 */
public class BackupUseCase {

    private final BookkeepingRepository repository;
    private final ContentResolver resolver;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public BackupUseCase(@NonNull Context context, @NonNull BookkeepingRepository repository) {
        this.resolver = context.getApplicationContext().getContentResolver();
        this.repository = repository;
    }

    /** 备份全部本地数据到 {@code uri}，主线程回调备份结果（含账单数）。 */
    public void backup(@NonNull Uri uri, @Nullable Callback<BackupResult> callback) {
        repository.runOnIo(() -> post(callback, writeBackup(uri)));
    }

    @NonNull
    private BackupResult writeBackup(@NonNull Uri uri) {
        OutputStream out = null;
        try {
            BackupData data = new BackupData();
            data.schemaVersion = BackupSerializer.SCHEMA_VERSION;
            data.accounts = repository.readAllAccounts();
            data.categories = repository.readAllCategories();
            data.transactions = repository.readAllTransactionEntities();
            data.budgets = repository.readAllBudgets();
            data.recurring = repository.readAllRecurring();
            data.settings = repository.readSettings();

            String json = BackupSerializer.toJson(data);
            out = resolver.openOutputStream(uri);
            if (out == null) {
                return BackupResult.failed();
            }
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return BackupResult.ok(data.transactions.size());
        } catch (IOException | JSONException | RuntimeException e) {
            return BackupResult.failed();
        } finally {
            closeQuietly(out);
        }
    }

    private static void closeQuietly(@Nullable OutputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // 关闭失败不影响备份结果
        }
    }

    private <T> void post(@Nullable Callback<T> callback, T value) {
        if (callback == null) {
            return;
        }
        mainHandler.post(() -> callback.onResult(value));
    }
}
