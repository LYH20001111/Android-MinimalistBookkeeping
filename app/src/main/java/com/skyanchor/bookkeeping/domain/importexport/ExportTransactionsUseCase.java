package com.skyanchor.bookkeeping.domain.importexport;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.entity.TransactionExport;
import com.skyanchor.bookkeeping.data.model.ExportResult;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.util.Callback;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 导出账单用例（V2 新增，开发计划 Phase 5）。
 *
 * <p>全量导出：在仓库的单线程 IO 上同步读取全部账单投影（含分类 / 账户名与时间戳），
 * 交 {@link CsvFormatter} 格式化为带 UTF-8 BOM 的 CSV，再写入用户经 SAF 选定的 {@link Uri}。
 * 读写全程在 IO 线程，结果回主线程回调，界面据此展示进度与成功 / 失败反馈。
 *
 * <p>不申请任何存储权限、不联网：目标位置完全由 SAF（{@code CreateDocument}）授权，
 * 与基线「本地优先、文件由用户选择位置」的约定一致。
 */
public class ExportTransactionsUseCase {

    private final BookkeepingRepository repository;
    private final ContentResolver resolver;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ExportTransactionsUseCase(@NonNull Context context,
                                     @NonNull BookkeepingRepository repository) {
        this.resolver = context.getApplicationContext().getContentResolver();
        this.repository = repository;
    }

    /** 导出全量账单为 CSV 写入 {@code uri}，主线程回调导出结果（含行数）。 */
    public void export(@NonNull Uri uri, @Nullable Callback<ExportResult> callback) {
        repository.runOnIo(() -> post(callback, writeCsv(uri)));
    }

    @NonNull
    private ExportResult writeCsv(@NonNull Uri uri) {
        OutputStream out = null;
        try {
            List<TransactionExport> rows = repository.readExportRows();
            String csv = CsvFormatter.toCsv(rows);
            out = resolver.openOutputStream(uri);
            if (out == null) {
                return ExportResult.failed();
            }
            out.write(csv.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return ExportResult.ok(rows.size());
        } catch (IOException | RuntimeException e) {
            return ExportResult.failed();
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
            // 关闭失败不影响导出结果
        }
    }

    private <T> void post(@Nullable Callback<T> callback, T value) {
        if (callback == null) {
            return;
        }
        mainHandler.post(() -> callback.onResult(value));
    }
}
