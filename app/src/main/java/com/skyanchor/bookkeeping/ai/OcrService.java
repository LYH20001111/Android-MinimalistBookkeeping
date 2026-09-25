package com.skyanchor.bookkeeping.ai;

import android.net.Uri;

import androidx.annotation.NonNull;

import com.skyanchor.bookkeeping.util.Callback;

import java.util.List;

/**
 * OCR 服务接口（V5 基线第 48、50 章）：只负责「图片 → 文字」，
 * 不做任何业务理解；实现可替换（端侧 ML Kit / 云端 OCR）。
 */
public interface OcrService {

    /**
     * 识别图片中的文字。
     *
     * @param imageUri 本地可读的图片地址（相册 content:// 或应用缓存 file://）
     * @param callback 主线程回调；无文字 / 图片不可读走
     *                 {@link Callback#onError}（{@link AiException.Kind#IMAGE_UNREADABLE}）
     */
    void recognize(@NonNull Uri imageUri, @NonNull Callback<OcrResult> callback);

    /** OCR 结果：按阅读顺序排列的文本行。 */
    final class OcrResult {

        /** 识别出的文本行；永不为 null（无文字时为空列表）。 */
        @NonNull
        public final List<OcrLine> lines;

        public OcrResult(@NonNull List<OcrLine> lines) {
            this.lines = lines;
        }

        /** 所有行按序拼接（换行分隔），供解析器消费。 */
        @NonNull
        public String joinedText() {
            StringBuilder sb = new StringBuilder();
            for (OcrLine line : lines) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line.text);
            }
            return sb.toString();
        }
    }

    /** 单行识别结果。 */
    final class OcrLine {
        @NonNull
        public final String text;
        /** 识别置信度 0~1，无法提供时为 -1。 */
        public final float confidence;

        public OcrLine(@NonNull String text, float confidence) {
            this.text = text;
            this.confidence = confidence;
        }
    }
}
