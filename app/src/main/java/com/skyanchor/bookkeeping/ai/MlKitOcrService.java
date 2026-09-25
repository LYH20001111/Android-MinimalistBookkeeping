package com.skyanchor.bookkeeping.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.skyanchor.bookkeeping.util.Callback;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 端侧 ML Kit 中文文字识别（V5）。模型随 APK 打包，离线可用、不依赖
 * Google Play Services（国内设备可正常工作）。
 *
 * <p>大图先按最长边 ≤ {@link #MAX_DIMENSION} 降采样并按 EXIF 摆正，
 * 再送识别，避免数十兆像素的照片直接解码导致 OOM。
 * 识别结果按阅读顺序逐行回调到主线程。
 */
public class MlKitOcrService implements OcrService {

    /** 送识别前图片的最长边，平衡识别率与内存。 */
    private static final int MAX_DIMENSION = 2048;

    private static final long AWAIT_TIMEOUT_SECONDS = 20L;

    private final Context context;
    private final TextRecognizer recognizer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public MlKitOcrService(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
    }

    @Override
    public void recognize(@NonNull Uri imageUri, @NonNull Callback<OcrResult> callback) {
        executor.execute(() -> {
            try {
                Bitmap bitmap = decodeScaled(imageUri);
                if (bitmap == null) {
                    mainHandler.post(() -> callback.onError(new AiException(
                            AiException.Kind.IMAGE_UNREADABLE, "图片无法解码")));
                    return;
                }
                List<OcrLine> lines = recognize(bitmap);
                bitmap.recycle();
                mainHandler.post(() -> callback.onResult(new OcrResult(lines)));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(new AiException(
                        AiException.Kind.IMAGE_UNREADABLE, "OCR 识别失败", e)));
            }
        });
    }

    @NonNull
    private List<OcrLine> recognize(@NonNull Bitmap bitmap)
            throws IOException, InterruptedException, java.util.concurrent.ExecutionException,
            java.util.concurrent.TimeoutException {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        Text visionText = Tasks.await(
                recognizer.process(image), AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        List<OcrLine> lines = new ArrayList<>();
        for (Text.TextBlock block : visionText.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    String text = line.getText();
                    if (text != null && !text.trim().isEmpty()) {
                        lines.add(new OcrLine(text.trim(), line.getConfidence()));
                    }
                }
        }
        return lines;
    }

    /** 按最长边降采样解码，并按 EXIF 方向摆正。 */
    @Nullable
    private Bitmap decodeScaled(@NonNull Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        readBitmap(uri, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION);
        Bitmap decoded = readBitmap(uri, options);
        if (decoded == null) {
            return null;
        }
        Bitmap rotated = applyExifRotation(uri, decoded);
        return rotated != null ? rotated : decoded;
    }

    @Nullable
    private Bitmap readBitmap(@NonNull Uri uri, @NonNull BitmapFactory.Options options)
            throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            return BitmapFactory.decodeStream(in, null, options);
        }
    }

    private static int sampleSize(int width, int height, int maxDimension) {
        int sample = 1;
        int max = Math.max(width, height);
        while (max / (sample * 2) >= maxDimension) {
            sample *= 2;
        }
        return sample;
    }

    /** 按 EXIF 方向旋转；读取失败或无需旋转时返回原图（或 null 表示原样使用）。 */
    @Nullable
    private Bitmap applyExifRotation(@NonNull Uri uri, @NonNull Bitmap source)
            throws IOException {
        int orientation;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            orientation = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        }
        int degrees;
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                degrees = 90;
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                degrees = 180;
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                degrees = 270;
                break;
            default:
                return null;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(),
                source.getHeight(), matrix, true);
        if (rotated != source) {
            source.recycle();
        }
        return rotated;
    }
}
