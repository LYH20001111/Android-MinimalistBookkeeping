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
import com.skyanchor.bookkeeping.data.model.BackupResult;
import com.skyanchor.bookkeeping.databinding.ActivityBackupBinding;
import com.skyanchor.bookkeeping.domain.importexport.BackupSerializer;
import com.skyanchor.bookkeeping.util.InsetsUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 本地备份（V2 新增，开发计划 Phase 7）。
 *
 * <p>点「备份到文件」→ SAF {@code CreateDocument} 让用户选择保存位置（默认文件名
 * {@code minimalist-bookkeeping-backup-v<版本>-<时间戳>.json}）→ 委托 {@link BackupViewModel}
 * 在 IO 线程写文件。全程不申请存储权限、不联网，与基线约定一致。
 * 备份进行中按钮显示「正在备份…」并禁用，避免重复触发。
 */
public class BackupActivity extends AppCompatActivity {

    private ActivityBackupBinding binding;
    private BackupViewModel viewModel;

    /** 最近一次已知的账单总数，用于「共 N 笔」文案；账单数为 0 也允许备份（空账本备份）。 */
    private boolean backingUp = false;

    private final ActivityResultLauncher<String> createDocument =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                    this::onDocumentCreated);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityBackupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applySystemBarsPadding(binding.backupRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        viewModel = new ViewModelProvider(this).get(BackupViewModel.class);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.backupButton.setOnClickListener(v -> createDocument.launch(defaultFileName()));

        viewModel.getTransactionCount().observe(this, count -> {
            int safe = count == null ? 0 : count;
            binding.backupCountValue.setText(getString(R.string.backup_count_format, safe));
        });
        viewModel.isBusy().observe(this, running -> {
            backingUp = running != null && running;
            refreshButton();
        });
        viewModel.getResult().observe(this, this::onBackupResult);
    }

    private void onDocumentCreated(@Nullable Uri uri) {
        // 用户取消选择时 uri 为 null，此时从未进入备份态，无需复位。
        if (uri != null) {
            viewModel.backup(uri);
        }
    }

    /** 默认文件名：minimalist-bookkeeping-backup-v3-yyyyMMdd-HHmmss.json。 */
    private String defaultFileName() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return getString(R.string.backup_file_prefix)
                + "-v" + BackupSerializer.SCHEMA_VERSION
                + "-" + stamp + ".json";
    }

    private void refreshButton() {
        binding.backupButton.setText(backingUp ? R.string.backup_running : R.string.backup_action);
        binding.backupButton.setEnabled(!backingUp);
    }

    private void onBackupResult(@Nullable BackupResult result) {
        if (result == null) {
            return;
        }
        viewModel.consumeResult();
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (result.success) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.backup_success_title)
                    .setMessage(getString(R.string.backup_success_message, result.transactionCount))
                    .setPositiveButton(R.string.action_confirm, null)
                    .show();
        } else {
            Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show();
        }
    }
}
