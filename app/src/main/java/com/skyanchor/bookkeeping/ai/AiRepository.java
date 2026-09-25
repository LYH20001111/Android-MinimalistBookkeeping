package com.skyanchor.bookkeeping.ai;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.util.Callback;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 智能记账仓库（V5 基线第 24、28 章）：编排「图片 → OCR → 清洗 → AI 结构化 →
 * 校验 → 草稿」与「文字 → AI 结构化 → 校验 → 草稿」两条管线。
 *
 * <p>UI 只与本类对话，不直接触碰 OCR / AI 实现。云端可用时云优先，
 * 失败自动降级本地规则解析；本地兜底后仍然失败（无内容可解析）才向 UI 报错，
 * 保证任何识别失败都不阻断手动记账（基线第 10、35 章）。
 */
public class AiRepository {

    private final Context context;
    private final AiConfigStore configStore;
    private final OcrService ocrService;
    private final CloudAiTransactionParser cloudParser;
    private final LocalRuleTransactionParser localParser;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public AiRepository(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.configStore = new AiConfigStore(this.context);
        this.ocrService = new MlKitOcrService(this.context);
        this.localParser = new LocalRuleTransactionParser();
        this.cloudParser = new CloudAiTransactionParser(configStore);
    }

    @NonNull
    public AiConfigStore getConfigStore() {
        return configStore;
    }

    /**
     * 扫描账单管线：识别图片 → 清洗 → 解析 → 草稿。
     *
     * @param imageUri   拍照或相册得到的图片地址；若为本应用缓存下的临时文件，
     *                   草稿生成后立即删除（基线第 32 章：不默认保存票据图片）
     */
    public void recognizeBill(@NonNull Uri imageUri,
                              @NonNull List<CategoryEntity> categories,
                              @NonNull Callback<TransactionDraft> callback) {
        ocrService.recognize(imageUri, new Callback<OcrService.OcrResult>() {
            @Override
            public void onResult(@Nullable OcrService.OcrResult result) {
                String text = result == null ? "" : OcrTextCleaner.clean(result.lines);
                if (text.trim().isEmpty()) {
                    callback.onError(new AiException(AiException.Kind.IMAGE_UNREADABLE,
                            "未识别到文字"));
                    return;
                }
                executor.execute(() -> parseAndDeliver(text, categories, callback,
                        () -> deleteTempImage(imageUri)));
            }

            @Override
            public void onError(@NonNull Exception e) {
                callback.onError(e);
            }
        });
    }

    /**
     * AI 文本记账管线：一句话描述 → 解析 → 草稿。
     */
    public void parseText(@NonNull String text,
                          @NonNull List<CategoryEntity> categories,
                          @NonNull Callback<TransactionDraft> callback) {
        executor.execute(() -> parseAndDeliver(text, categories, callback, null));
    }

    // ------------------------------------------------------------------
    // 内部管线
    // ------------------------------------------------------------------

    /** 在 IO 线程执行解析：云优先、失败降级本地，最终统一走 {@link #deliver}。 */
    private void parseAndDeliver(@NonNull String text,
                                 @NonNull List<CategoryEntity> categories,
                                 @NonNull Callback<TransactionDraft> callback,
                                 @Nullable Runnable onFinished) {
        if (configStore.isCloudConfigured()) {
            cloudParser.parse(text, categories, new Callback<TransactionDraft>() {
                @Override
                public void onResult(@Nullable TransactionDraft draft) {
                    if (draft == null) {
                        fallbackToLocal(text, categories, callback, onFinished);
                        return;
                    }
                    deliver(draft, callback, onFinished);
                }

                @Override
                public void onError(@NonNull Exception e) {
                    fallbackToLocal(text, categories, callback, onFinished);
                }
            });
        } else {
            localParser.parse(text, categories, draft -> deliver(draft, callback, onFinished));
        }
    }

    private void fallbackToLocal(@NonNull String text,
                                 @NonNull List<CategoryEntity> categories,
                                 @NonNull Callback<TransactionDraft> callback,
                                 @Nullable Runnable onFinished) {
        localParser.parse(text, categories, draft -> deliver(draft, callback, onFinished));
    }

    /** 收尾：草稿补默认值后回调 UI，并清理临时图片。 */
    private void deliver(@NonNull TransactionDraft draft,
                         @NonNull Callback<TransactionDraft> callback,
                         @Nullable Runnable onFinished) {
        DraftValidator.normalize(draft, System.currentTimeMillis());
        callback.onResult(draft);
        if (onFinished != null) {
            executor.execute(onFinished);
        }
    }

    /** 仅删除本应用缓存目录下的临时扫描图，相册 content:// 不动。 */
    private void deleteTempImage(@NonNull Uri imageUri) {
        if (!"file".equals(imageUri.getScheme())) {
            return;
        }
        File file = new File(imageUri.getPath());
        File cacheDir = context.getCacheDir();
        if (file.getPath().startsWith(cacheDir.getPath())) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
