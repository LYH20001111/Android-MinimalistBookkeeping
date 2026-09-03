package com.skyanchor.bookkeeping.ui.importexport;

import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.model.ExportResult;
import com.skyanchor.bookkeeping.databinding.ActivityDataExportBinding;
import com.skyanchor.bookkeeping.util.InsetsUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 数据导出（V2 新增，开发计划 Phase 5）。
 *
 * <p>点「导出 CSV 文件」→ SAF {@code CreateDocument} 让用户选择保存位置 → 委托
 * {@link ExportViewModel} 在 IO 线程写文件。全程不申请存储权限、不联网，与基线约定一致。
 * 账单数为 0 时禁用导出；导出进行中按钮显示「正在导出…」并禁用，避免重复触发。
 */
public class DataExportActivity extends AppCompatActivity {

    private ActivityDataExportBinding binding;
    private ExportViewModel viewModel;

    /** 最近一次已知的账单总数，用于生成按钮可用态与「共 N 笔」文案。 */
    private int lastCount = 0;
    private boolean exporting = false;

    private final ActivityResultLauncher<String> createDocument =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"),
                    this::onDocumentCreated);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDataExportBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applySystemBarsPadding(binding.exportRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        viewModel = new ViewModelProvider(this).get(ExportViewModel.class);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.exportButton.setOnClickListener(v -> launchExport());

        viewModel.getTransactionCount().observe(this, count -> {
            lastCount = count == null ? 0 : count;
            binding.exportCountValue.setText(getString(R.string.export_count_format, lastCount));
            refreshExportButton();
        });
        viewModel.isExporting().observe(this, running -> {
            exporting = running != null && running;
            refreshExportButton();
        });
        viewModel.getResult().observe(this, this::onExportResult);
    }

    private void onDocumentCreated(@Nullable Uri uri) {
        // 用户取消选择时 uri 为 null，此时从未进入导出态，无需复位。
        if (uri != null) {
            viewModel.export(uri);
        }
    }

    private void launchExport() {
        if (exporting) {
            return;
        }
        if (lastCount <= 0) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        createDocument.launch(defaultFileName());
    }

    /** 默认文件名：极简记账-账单-yyyyMMdd-HHmmss.csv。 */
    private String defaultFileName() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return getString(R.string.export_file_prefix) + "-" + stamp + ".csv";
    }

    private void refreshExportButton() {
        binding.exportButton.setText(exporting ? R.string.export_running : R.string.export_action);
        binding.exportButton.setEnabled(!exporting && lastCount > 0);
    }

    private void onExportResult(@Nullable ExportResult result) {
        if (result == null) {
            return;
        }
        viewModel.consumeResult();
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (result.success) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.export_success_title)
                    .setMessage(getString(R.string.export_success_message, result.count))
                    .setPositiveButton(R.string.action_confirm, null)
                    .show();
        } else {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show();
        }
    }
}
