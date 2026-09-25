package com.skyanchor.bookkeeping.ui.smart;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.ai.AiException;
import com.skyanchor.bookkeeping.ai.TransactionDraft;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.databinding.ActivityScanBillBinding;
import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.util.InsetsUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 扫描账单页（V5）。拍照 / 相册选图 → OCR + AI 结构化 → 账单确认页。
 *
 * <p>失败不阻断：任何识别错误都给出「重拍 / 换图 / 手动填写」出路
 * （基线第 9、10 章），手动填写即返回「记一笔」页。
 */
public class ScanBillActivity extends AppCompatActivity {

    private ActivityScanBillBinding binding;
    private ScanBillViewModel viewModel;

    /** 分类快照：识别需要把分类名对齐到真实 categoryId，取自 LiveData 最新值。 */
    @NonNull
    private List<CategoryEntity> categories = new ArrayList<>();

    /** 本次拍照的临时地址，回调成功后交给识别管线。 */
    @Nullable
    private Uri pendingCaptureUri;

    /** 确认页结果：确认落库 OK 后本页也带 OK 关闭，逐级回到记录页。 */
    private final ActivityResultLauncher<Intent> confirmLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    setResult(RESULT_OK);
                    finish();
                }
            });

    private final ActivityResultLauncher<Uri> takePictureLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (Boolean.TRUE.equals(success) && pendingCaptureUri != null) {
                    viewModel.process(pendingCaptureUri, categories);
                }
            });

    private final ActivityResultLauncher<Intent> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                Intent data = result.getData();
                Uri picked = result.getResultCode() == RESULT_OK && data != null
                        ? data.getData() : null;
                handlePickedImage(picked);
            });

    /** 兜底选图：极少数没有相册应用的机型，退回系统 Photo Picker。 */
    private final ActivityResultLauncher<PickVisualMediaRequest> pickImageFallbackLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(),
                    this::handlePickedImage);

    private void handlePickedImage(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        // 相册授权是瞬时的，先在本进程内复制为临时文件再识别，
        // 避免「识别中切后台 → 授权失效」的边界情况。
        Uri local = copyToCache(uri);
        if (local != null) {
            viewModel.process(local, categories);
        } else {
            showError(new AiException(AiException.Kind.IMAGE_UNREADABLE,
                    "图片读取失败"));
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityScanBillBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applyTopAndHorizontalPadding(binding.scanRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        viewModel = new ViewModelProvider(this).get(ScanBillViewModel.class);

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.takePhotoButton.setOnClickListener(v -> takePhoto());
        binding.pickImageButton.setOnClickListener(v -> pickImage());
        binding.fillManuallyButton.setOnClickListener(v -> finish());

        viewModel.getStatus().observe(this, this::renderStatus);
        viewModel.getError().observe(this, this::showError);

        BookkeepingRepository repository = BookkeepingApp.get(this).getRepository();
        repository.observeAllCategories().observe(this, list ->
                categories = list == null ? new ArrayList<>() : list);
    }

    // ------------------------------------------------------------------
    // 图片来源
    // ------------------------------------------------------------------

    private void takePhoto() {
        try {
            Uri uri = createCaptureUri();
            pendingCaptureUri = uri;
            takePictureLauncher.launch(uri);
        } catch (IOException e) {
            showError(new AiException(AiException.Kind.IMAGE_UNREADABLE, "创建临时图片失败", e));
        }
    }

    private void pickImage() {
        // 相册 App 统一响应 ACTION_PICK（微信选图同款路径）；
        // Photo Picker 在无 GMS 媒体模块的国产 ROM 上会退化成文件管理器，
        // 故以相册为主入口，仅在没有应用响应时才退回 Photo Picker。
        try {
            pickImageLauncher.launch(new Intent(Intent.ACTION_PICK,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI));
        } catch (ActivityNotFoundException e) {
            pickImageFallbackLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        }
    }

    /** 拍照临时文件：cacheDir/smart_images，识别完成后由 AiRepository 删除。 */
    @NonNull
    private Uri createCaptureUri() throws IOException {
        File dir = new File(getCacheDir(), "smart_images");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建缓存目录");
        }
        File file = File.createTempFile("scan_", ".jpg", dir);
        return FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", file);
    }

    /** 把相册图片复制进应用缓存，返回可长期读取的本地地址；失败返回 null。 */
    @Nullable
    private Uri copyToCache(@NonNull Uri source) {
        try {
            File dir = new File(getCacheDir(), "smart_images");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File target = File.createTempFile("pick_", ".jpg", dir);
            try (java.io.InputStream in = getContentResolver().openInputStream(source);
                 java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                if (in == null) {
                    return null;
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
            return Uri.fromFile(target);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 状态渲染
    // ------------------------------------------------------------------

    private void renderStatus(@Nullable ScanBillViewModel.Status status) {
        boolean processing = status == ScanBillViewModel.Status.PROCESSING;
        binding.scanIdle.setVisibility(processing ? View.GONE : View.VISIBLE);
        binding.scanProcessing.setVisibility(processing ? View.VISIBLE : View.GONE);
        binding.takePhotoButton.setEnabled(!processing);
        binding.pickImageButton.setEnabled(!processing);

        if (status == ScanBillViewModel.Status.SUCCESS) {
            TransactionDraft draft = viewModel.consumeDraft();
            if (draft != null) {
                confirmLauncher.launch(ConfirmBillActivity.newIntent(this, draft));
            }
        }
    }

    private void showError(@Nullable AiException error) {
        if (error == null) {
            return;
        }
        viewModel.reset();
        viewModel.consumeError();
        boolean unreadable = error.kind == AiException.Kind.IMAGE_UNREADABLE;
        int message = unreadable ? R.string.scan_error_unreadable : R.string.scan_error_parse;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.scan_title)
                .setMessage(message)
                .setNegativeButton(R.string.action_fill_manually, (dialog, which) -> finish());
        if (unreadable) {
            builder.setPositiveButton(R.string.scan_action_retake, (dialog, which) -> takePhoto())
                    .setNeutralButton(R.string.scan_action_pick_other,
                            (dialog, which) -> pickImage());
        } else {
            builder.setPositiveButton(R.string.action_retry,
                    (dialog, which) -> viewModel.retry(categories));
        }
        builder.show();
    }
}
