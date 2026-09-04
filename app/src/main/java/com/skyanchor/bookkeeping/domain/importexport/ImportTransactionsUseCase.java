package com.skyanchor.bookkeeping.domain.importexport;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.model.ImportCommitResult;
import com.skyanchor.bookkeeping.data.model.ImportPreview;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.util.Callback;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 导入账单用例（V2 新增，开发计划 Phase 5）。
 *
 * <p>两段式，杜绝静默写入：
 * <ol>
 *   <li>{@link #preview}：读取 SAF Uri 的 CSV → 以当前分类 / 账户 / 既有账单构建 {@link ImportContext}
 *       → 交 {@link ImportRowClassifier} 逐行分类为 {@link ImportPreview}（有效 / 跳过 / 错误 + 原因）；</li>
 *   <li>{@link #commit}：用户在预览页确认后，只把有效行交仓库在单事务内批量插入并重算受影响账户余额，
 *       回调实际插入行数。</li>
 * </ol>
 *
 * <p>读文件与解析在仓库单线程 IO 上进行（与其他写操作串行，不并发访问数据库），结果回主线程。
 * 读取失败或表头缺必填列统一回落 {@link ImportPreview#INVALID_HEADER}，界面提示格式不正确。
 */
public class ImportTransactionsUseCase {

    private final BookkeepingRepository repository;
    private final ContentResolver resolver;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ImportTransactionsUseCase(@NonNull Context context,
                                     @NonNull BookkeepingRepository repository) {
        this.resolver = context.getApplicationContext().getContentResolver();
        this.repository = repository;
    }

    /** 解析 SAF Uri 指向的 CSV，生成导入预览，主线程回调。 */
    public void preview(@NonNull Uri uri, @Nullable Callback<ImportPreview> callback) {
        repository.runOnIo(() -> post(callback, buildPreview(uri)));
    }

    /**
     * 用户确认后提交预览中的可提交行，主线程回调提交结果（含插入行数）。
     * V2.1：可提交 = 全部有效行 + 用户在预览页选择「保留」的疑似重复行（默认跳过）。
     */
    public void commit(@NonNull ImportPreview preview,
                       @Nullable Callback<ImportCommitResult> callback) {
        repository.runOnIo(() -> {
            ImportCommitResult result;
            try {
                int inserted = repository.insertImportedTransactions(preview.commitEntities());
                result = ImportCommitResult.ok(inserted);
            } catch (RuntimeException e) {
                result = ImportCommitResult.failed();
            }
            post(callback, result);
        });
    }

    @NonNull
    private ImportPreview buildPreview(@NonNull Uri uri) {
        String text = readText(uri);
        if (text == null) {
            return ImportPreview.INVALID_HEADER;
        }
        ImportContext context = new ImportContext(
                repository.readAllCategories(),
                repository.readAllAccounts(),
                repository.readAllTransactionEntities());
        return ImportRowClassifier.classify(text, context);
    }

    /** 读取 Uri 全部字节并按 UTF-8 解码；失败返回 null。 */
    @Nullable
    private String readText(@NonNull Uri uri) {
        InputStream in = null;
        try {
            in = resolver.openInputStream(uri);
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(@Nullable InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // 关闭失败不影响解析结果
        }
    }

    private <T> void post(@Nullable Callback<T> callback, T value) {
        if (callback == null) {
            return;
        }
        mainHandler.post(() -> callback.onResult(value));
    }
}
