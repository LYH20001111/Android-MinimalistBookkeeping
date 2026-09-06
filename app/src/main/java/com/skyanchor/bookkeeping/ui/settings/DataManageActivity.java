package com.skyanchor.bookkeeping.ui.settings;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.databinding.ActivityDataManageBinding;
import com.skyanchor.bookkeeping.databinding.DialogAdminVerifyBinding;
import com.skyanchor.bookkeeping.util.AdminVerifyStore;
import com.skyanchor.bookkeeping.util.InsetsUtil;
import com.skyanchor.bookkeeping.util.ThemeStore;

/**
 * 数据管理（V1 基线第 9 章）。
 *
 * <p>展示三张业务表的存量，并提供「清空所有本地数据」这一不可恢复操作。
 * V1 不含导出/导入与云同步，因此这里没有任何网络相关入口。
 *
 * <p>V3：清空前需先通过管理员验证，连续输错三次锁定三分钟。失败计数与锁定截止
 * 时间经 {@link AdminVerifyStore} 持久化，返回我的页面或重启应用都不解除。
 */
public class DataManageActivity extends AppCompatActivity {

    private static final String ADMIN_ACCOUNT = "admin";
    private static final String ADMIN_PASSWORD = "11111029";
    private static final int MAX_VERIFY_ATTEMPTS = 3;
    private static final long VERIFY_LOCK_MILLIS = 3L * 60L * 1000L;
    private static final long LOCK_TICK_MILLIS = 1000L;

    private ActivityDataManageBinding binding;
    private BookkeepingRepository repository;

    // 仅承载锁定倒计时的 UI 刷新；锁定状态本身在 AdminVerifyStore
    private final Handler lockHandler = new Handler(Looper.getMainLooper());
    @Nullable
    private Runnable lockCountdown;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDataManageBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applySystemBarsPadding(binding.dataRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        repository = BookkeepingApp.get(this).getRepository();
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.clearDataButton.setOnClickListener(v -> showAdminVerifyDialog());
        // V3.1：回收站（软删数据可找回，基线第 18 章）
        binding.recycleBinRow.setOnClickListener(v ->
                startActivity(com.skyanchor.bookkeeping.ui.sync.RecycleBinActivity
                        .newIntent(this)));

        // 计数都走 LiveData，清空后无需手动刷新即可回落到默认值
        repository.observeTransactionCount().observe(this,
                count -> renderCount(binding.dataTransactionValue, count));
        repository.observeCategoryCount().observe(this,
                count -> renderCount(binding.dataCategoryValue, count));
        repository.observeBudgetCount().observe(this,
                count -> renderCount(binding.dataBudgetValue, count));
        // V2 Phase 9：账户与周期账单纳入存量统计
        repository.observeAccountCount().observe(this,
                count -> renderCount(binding.dataAccountValue, count));
        repository.observeRecurringCount().observe(this,
                count -> renderCount(binding.dataRecurringValue, count));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        lockHandler.removeCallbacksAndMessages(null);
    }

    private void renderCount(@NonNull TextView view, @Nullable Integer count) {
        view.setText(getString(R.string.data_count_format, count == null ? 0 : count));
    }

    // ------------------------------------------------------------------
    // 管理员验证
    // ------------------------------------------------------------------

    /** 清空前的管理员验证弹窗；验证通过后才进入原有的二次确认。 */
    private void showAdminVerifyDialog() {
        DialogAdminVerifyBinding dialogBinding =
                DialogAdminVerifyBinding.inflate(getLayoutInflater());
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.data_clear_verify_title)
                .setMessage(R.string.data_clear_verify_message)
                .setView(dialogBinding.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.data_clear_verify_confirm, null)
                .create();
        dialog.show();

        dialogBinding.adminAccountInput.addTextChangedListener(
                new ClearErrorWatcher(dialogBinding.adminAccountLayout));
        dialogBinding.adminPasswordInput.addTextChangedListener(
                new ClearErrorWatcher(dialogBinding.adminPasswordLayout));

        // 密码框点键盘上的「完成」即触发验证
        dialogBinding.adminPasswordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                return true;
            }
            return false;
        });

        // 确定按钮改为手动关闭：验证不通过就留在弹窗内就地提示
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                v -> verifyAdmin(dialog, dialogBinding));

        dialog.setOnDismissListener(d -> stopLockCountdown());

        // 锁定期间关闭再打开、甚至重启应用，锁定都仍然生效
        if (AdminVerifyStore.isLocked(this)) {
            enterLockdown(dialog, dialogBinding);
        }
    }

    private void verifyAdmin(@NonNull AlertDialog dialog,
                             @NonNull DialogAdminVerifyBinding dialogBinding) {
        if (AdminVerifyStore.isLocked(this)) {
            return;
        }
        Editable accountEditable = dialogBinding.adminAccountInput.getText();
        String account = accountEditable == null ? "" : accountEditable.toString().trim();
        Editable passwordEditable = dialogBinding.adminPasswordInput.getText();
        String password = passwordEditable == null ? "" : passwordEditable.toString();

        if (account.isEmpty() || password.isEmpty()) {
            dialogBinding.adminAccountLayout.setError(
                    getString(R.string.data_clear_verify_error_empty));
            return;
        }

        if (ADMIN_ACCOUNT.equals(account) && ADMIN_PASSWORD.equals(password)) {
            AdminVerifyStore.reset(this);
            dialog.dismiss();
            confirmClear();
            return;
        }

        int attempts = AdminVerifyStore.recordFailure(this);
        if (attempts >= MAX_VERIFY_ATTEMPTS) {
            AdminVerifyStore.lock(this, VERIFY_LOCK_MILLIS);
            enterLockdown(dialog, dialogBinding);
        } else {
            dialogBinding.adminPasswordLayout.setError(getString(
                    R.string.data_clear_verify_error_credentials,
                    MAX_VERIFY_ATTEMPTS - attempts));
        }
    }

    /** 锁定期间禁用输入与验证按钮，每秒刷新剩余等待时间；到期自动解锁。 */
    private void enterLockdown(@NonNull AlertDialog dialog,
                               @NonNull DialogAdminVerifyBinding dialogBinding) {
        dialogBinding.adminAccountInput.setEnabled(false);
        dialogBinding.adminPasswordInput.setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);

        stopLockCountdown();
        lockCountdown = new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing()) {
                    return;
                }
                long remainingMillis = AdminVerifyStore.lockUntil(DataManageActivity.this)
                        - System.currentTimeMillis();
                if (remainingMillis <= 0) {
                    AdminVerifyStore.reset(DataManageActivity.this);
                    exitLockdown(dialog, dialogBinding);
                    return;
                }
                long minutes = remainingMillis / 60000L;
                long seconds = (remainingMillis / 1000L) % 60L;
                dialogBinding.adminPasswordLayout.setError(getString(
                        R.string.data_clear_verify_locked_format, minutes, seconds));
                lockHandler.postDelayed(this, LOCK_TICK_MILLIS);
            }
        };
        lockCountdown.run();
    }

    private void exitLockdown(@NonNull AlertDialog dialog,
                              @NonNull DialogAdminVerifyBinding dialogBinding) {
        stopLockCountdown();
        dialogBinding.adminAccountInput.setEnabled(true);
        dialogBinding.adminPasswordInput.setEnabled(true);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
        dialogBinding.adminAccountLayout.setError(null);
        dialogBinding.adminPasswordLayout.setError(null);
    }

    private void stopLockCountdown() {
        if (lockCountdown != null) {
            lockHandler.removeCallbacks(lockCountdown);
            lockCountdown = null;
        }
    }

    // ------------------------------------------------------------------
    // 清空（验证通过后的既有二次确认）
    // ------------------------------------------------------------------

    /** 不可恢复操作必须二次确认，并在文案里明示后果。 */
    private void confirmClear() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.data_clear_dialog_title)
                .setMessage(R.string.data_clear_dialog_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm, (dialog, which) -> clear())
                .show();
    }

    private void clear() {
        binding.clearDataButton.setEnabled(false);
        repository.clearAllData(cleared -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            binding.clearDataButton.setEnabled(true);
            if (cleared == null || !cleared) {
                return;
            }
            Toast.makeText(this, R.string.data_cleared, Toast.LENGTH_SHORT).show();
            syncNightMode();
        });
    }

    /** 清空会把主题重置为浅色，若当前正处于深色，需要同步一次夜间模式，避免界面与设置不一致。 */
    private void syncNightMode() {
        int mode = ThemeStore.nightMode(ThemeStore.get(this));
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode);
        }
    }

    /** 输入变化即清除对应输入框的错误提示，与账户编辑弹窗的手感一致。 */
    private static final class ClearErrorWatcher implements TextWatcher {

        private final TextInputLayout layout;

        ClearErrorWatcher(TextInputLayout layout) {
            this.layout = layout;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            layout.setError(null);
        }
    }
}
