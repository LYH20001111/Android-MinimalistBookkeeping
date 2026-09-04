package com.skyanchor.bookkeeping.ui.importexport;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.model.ImportCommitResult;
import com.skyanchor.bookkeeping.data.model.ImportPreview;
import com.skyanchor.bookkeeping.databinding.ActivityDataImportBinding;
import com.skyanchor.bookkeeping.ui.adapter.ImportPreviewAdapter;
import com.skyanchor.bookkeeping.util.InsetsUtil;

/**
 * 数据导入（V2 新增，开发计划 Phase 5）。
 *
 * <p>两段式导入：点「选择 CSV 文件」→ SAF {@code OpenDocument} 选文件 → 委托 {@link ImportViewModel}
 * 在 IO 线程解析并生成逐行预览（有效 / 重复跳过 / 错误，各自标注原因）；用户确认后批量写入单个事务，
 * 弹出「成功 / 跳过 / 错误」结果对话框。全程不申请存储权限、不联网，与基线约定一致。
 *
 * <p>解析与提交共用一个进行态 {@code busy}，用本地 {@link #committing} 区分按钮文案（正在解析 / 正在导入）。
 * 表头非法或无数据行时把提示写进占位区而非弹对话框，避免旋转重建时重复弹出。
 */
public class DataImportActivity extends AppCompatActivity {

    private ActivityDataImportBinding binding;
    private ImportViewModel viewModel;
    private ImportPreviewAdapter adapter;

    /** 进行态：解析中或导入中，两阶段共用一个 busy。 */
    private boolean busy = false;
    /** 区分 busy 是「提交导入」还是「解析文件」，仅影响按钮文案。 */
    private boolean committing = false;
    /** 成功导入后置位，禁止在结果对话框期间再次提交（对话框确认后关闭页面）。 */
    private boolean committed = false;

    private final ActivityResultLauncher<String[]> openDocument =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    this::onDocumentOpened);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDataImportBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applySystemBarsPadding(binding.importRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        viewModel = new ViewModelProvider(this).get(ImportViewModel.class);
        // 疑似重复行切换「保留 / 跳过」后即时刷新确认按钮与统计（V2.1 基线第 17 章）
        adapter = new ImportPreviewAdapter(this::refreshButtons);
        binding.importPreviewList.setLayoutManager(new LinearLayoutManager(this));
        binding.importPreviewList.setAdapter(adapter);

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.pickFileButton.setOnClickListener(v -> launchPick());
        binding.confirmImportButton.setOnClickListener(v -> {
            committing = true;
            viewModel.commit();
        });

        // 先订阅预览、再订阅进行态：旋转重建时预览先恢复列表与合计，进行态再据此刷新按钮文案。
        viewModel.getPreview().observe(this, this::onPreviewChanged);
        viewModel.isBusy().observe(this, this::onBusyChanged);
        viewModel.getCommitResult().observe(this, this::onCommitResult);
    }

    private void launchPick() {
        if (busy) {
            return;
        }
        // 宽松 mime：不同文件管理器对 CSV 的类型标注不一，内容合法性交给解析器判定。
        openDocument.launch(new String[]{"*/*"});
    }

    private void onDocumentOpened(@Nullable Uri uri) {
        // 用户取消选择时 uri 为 null，此时从未进入解析态，无需复位。
        if (uri != null) {
            committing = false;
            viewModel.loadPreview(uri);
        }
    }

    private void onBusyChanged(@Nullable Boolean running) {
        busy = running != null && running;
        if (!busy) {
            committing = false;
        }
        refreshButtons();
    }

    private void onPreviewChanged(@Nullable ImportPreview preview) {
        if (preview == null) {
            return;
        }
        if (!preview.headerValid) {
            // 表头缺必填列：清空列表，占位区改提示「文件格式不正确」，不弹对话框以免旋转重复弹。
            showPlaceholder(R.string.import_invalid_format);
            return;
        }
        if (preview.isEmpty()) {
            // 表头合法但没有数据行。
            showPlaceholder(R.string.import_empty);
            return;
        }
        adapter.submit(preview.rows);
        binding.importEmpty.setVisibility(View.GONE);
        binding.importSummary.setVisibility(View.VISIBLE);
        binding.importSummary.setText(getString(R.string.import_summary_format,
                preview.validCount, preview.duplicateCount, preview.errorCount));
        binding.importHint.setVisibility(View.VISIBLE);
        binding.confirmImportButton.setVisibility(View.VISIBLE);
        refreshButtons();
    }

    /** 无有效预览（表头非法 / 空文件）时复位为占位提示，隐藏合计与确认按钮。 */
    private void showPlaceholder(int messageRes) {
        adapter.submit(null);
        binding.importSummary.setVisibility(View.GONE);
        binding.importHint.setVisibility(View.GONE);
        binding.confirmImportButton.setVisibility(View.GONE);
        binding.importEmpty.setVisibility(View.VISIBLE);
        binding.importEmpty.setText(messageRes);
        refreshButtons();
    }

    private void refreshButtons() {
        ImportPreview preview = viewModel.currentPreview();
        boolean hasPreview = preview != null && preview.headerValid && !preview.isEmpty();
        boolean parsing = busy && !committing;

        binding.pickFileButton.setText(parsing ? R.string.import_parsing : R.string.import_pick);
        binding.pickFileButton.setEnabled(!busy);

        if (hasPreview) {
            // 确认数 = 有效行 + 用户保留的疑似重复行（默认跳过，不静默写入也不静默丢弃）
            int committable = preview.validCount + preview.keptDuplicateCount();
            boolean importing = busy && committing;
            binding.confirmImportButton.setText(importing
                    ? getString(R.string.import_committing)
                    : getString(R.string.import_confirm_format, committable));
            binding.confirmImportButton.setEnabled(!busy && !committed && preview.hasCommittable());
        }
    }

    private void onCommitResult(@Nullable ImportCommitResult result) {
        if (result == null) {
            return;
        }
        viewModel.consumeCommitResult();
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (result.success) {
            committed = true;
            refreshButtons();
            ImportPreview preview = viewModel.currentPreview();
            // 跳过数 = 仍被跳过的疑似重复行；被保留的重复行已计入成功导入数
            int duplicate = preview == null ? 0 : preview.skippedDuplicateCount();
            int error = preview == null ? 0 : preview.errorCount;
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.import_success_title)
                    .setMessage(getString(R.string.import_success_message,
                            result.inserted, duplicate, error))
                    .setCancelable(false)
                    .setPositiveButton(R.string.action_confirm, (dialog, which) -> finish())
                    .show();
        } else {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show();
        }
    }
}
