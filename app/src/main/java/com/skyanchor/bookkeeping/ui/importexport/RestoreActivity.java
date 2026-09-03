package com.skyanchor.bookkeeping.ui.importexport;

import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.model.BackupResult;
import com.skyanchor.bookkeeping.data.model.RestoreResult;
import com.skyanchor.bookkeeping.databinding.ActivityRestoreBinding;
import com.skyanchor.bookkeeping.domain.importexport.BackupSerializer;
import com.skyanchor.bookkeeping.util.InsetsUtil;
import com.skyanchor.bookkeeping.util.ThemeStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 本地恢复（V2 新增，开发计划 Phase 7）。
 *
 * <p>覆盖恢复是破坏性操作，流程刻意分三步：
 * <ol>
 *   <li>SAF {@code OpenDocument} 选择备份文件；</li>
 *   <li>二次确认弹窗明示「将覆盖当前本地数据」，并提供「先备份当前数据」
 *       （先把现状备份为一个新的 JSON 文件，完成后再回到确认）；</li>
 *   <li>用户明确确认后才执行恢复，恢复在单个事务内完成，失败自动回滚。</li>
 * </ol>
 * 恢复成功后若备份文件里的主题与当前不同，立即按恢复后的设置应用夜间模式。
 */
public class RestoreActivity extends AppCompatActivity {

    private ActivityRestoreBinding binding;
    private RestoreViewModel viewModel;

    private boolean restoring = false;

    private final ActivityResultLauncher<String[]> openDocument =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    this::onDocumentPicked);

    /** 「先备份当前数据」分支使用的保存位置选择器。 */
    private final ActivityResultLauncher<String> createDocument =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                    this::onBackupDocumentCreated);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRestoreBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applySystemBarsPadding(binding.restoreRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        viewModel = new ViewModelProvider(this).get(RestoreViewModel.class);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.restoreButton.setOnClickListener(v -> pickBackupFile());

        viewModel.isBusy().observe(this, running -> {
            restoring = running != null && running;
            refreshButton();
        });
        viewModel.getBackupResult().observe(this, this::onBackupFirstResult);
        viewModel.getRestoreResult().observe(this, this::onRestoreResult);
    }

    private void pickBackupFile() {
        if (restoring) {
            return;
        }
        openDocument.launch(new String[]{"application/json"});
    }

    private void onDocumentPicked(@Nullable Uri uri) {
        // 用户取消选择时 uri 为 null，直接忽略。
        if (uri == null || isFinishing() || isDestroyed()) {
            return;
        }
        confirmRestore(uri);
    }

    /**
     * 二次确认：明示「将覆盖当前本地数据」，取消 / 先备份当前数据 / 仍要恢复 三选一。
     */
    private void confirmRestore(@NonNull Uri uri) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.restore_confirm_title)
                .setMessage(R.string.restore_confirm_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setNeutralButton(R.string.restore_confirm_backup_first, (dialog, which) -> {
                    viewModel.setPendingRestore(uri);
                    createDocument.launch(backupFileName());
                })
                .setPositiveButton(R.string.restore_confirm_proceed,
                        (dialog, which) -> viewModel.restore(uri))
                .show();
    }

    private void onBackupDocumentCreated(@Nullable Uri uri) {
        if (uri == null) {
            // 用户取消了保存位置选择：保留待恢复文件，用户可重新走「先备份」或「仍要恢复」。
            return;
        }
        if (viewModel.hasPendingRestore()) {
            viewModel.backupFirst(uri);
        }
    }

    /** 「先备份当前数据」完成：成功则回到恢复确认，失败则提示并清除待恢复状态。 */
    private void onBackupFirstResult(@Nullable BackupResult result) {
        if (result == null) {
            return;
        }
        viewModel.consumeBackupResult();
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Uri pending = viewModel.getPendingRestoreUri();
        if (!result.success) {
            viewModel.clearPendingRestore();
            Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (pending == null) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.restore_backup_done_title)
                .setMessage(R.string.restore_backup_done_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.restore_confirm_proceed,
                        (dialog, which) -> viewModel.restore(pending))
                .show();
    }

    private void onRestoreResult(@Nullable RestoreResult result) {
        if (result == null) {
            return;
        }
        viewModel.consumeRestoreResult();
        viewModel.clearPendingRestore();
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (!result.success) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.restore_title)
                    .setMessage(reasonText(result.reason))
                    .setPositiveButton(R.string.action_confirm, null)
                    .show();
            return;
        }
        // 备份里的主题可能与当前不同：立即按恢复后的设置应用，避免用户看到旧主题以为恢复失败
        ThemeStore.apply(this);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.restore_success_title)
                .setMessage(getString(R.string.restore_success_message,
                        result.transactionCount, result.accountCount, result.categoryCount,
                        result.budgetCount, result.recurringCount))
                .setPositiveButton(R.string.action_confirm, null)
                .show();
    }

    private String reasonText(int reason) {
        switch (reason) {
            case RestoreResult.REASON_IO:
                return getString(R.string.restore_reason_io);
            case RestoreResult.REASON_MALFORMED:
                return getString(R.string.restore_reason_malformed);
            case RestoreResult.REASON_VERSION:
                return getString(R.string.restore_reason_version);
            case RestoreResult.REASON_INVALID:
                return getString(R.string.restore_reason_invalid);
            default:
                return getString(R.string.restore_reason_invalid);
        }
    }

    /** 「先备份」默认文件名，与备份页同规则：…-v3-时间戳.json。 */
    private String backupFileName() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return getString(R.string.backup_file_prefix)
                + "-v" + BackupSerializer.SCHEMA_VERSION
                + "-" + stamp + ".json";
    }

    private void refreshButton() {
        binding.restoreButton.setText(restoring ? R.string.restore_running : R.string.restore_action);
        binding.restoreButton.setEnabled(!restoring);
    }
}
