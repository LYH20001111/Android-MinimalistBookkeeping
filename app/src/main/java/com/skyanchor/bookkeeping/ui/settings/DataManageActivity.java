package com.skyanchor.bookkeeping.ui.settings;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.databinding.ActivityDataManageBinding;
import com.skyanchor.bookkeeping.util.InsetsUtil;
import com.skyanchor.bookkeeping.util.ThemeStore;

/**
 * 数据管理（V1 基线第 9 章）。
 *
 * <p>展示三张业务表的存量，并提供「清空所有本地数据」这一不可恢复操作。
 * V1 不含导出/导入与云同步，因此这里没有任何网络相关入口。
 */
public class DataManageActivity extends AppCompatActivity {

    private ActivityDataManageBinding binding;
    private BookkeepingRepository repository;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDataManageBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applySystemBarsPadding(binding.dataRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        repository = BookkeepingApp.get(this).getRepository();
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.clearDataButton.setOnClickListener(v -> confirmClear());

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

    private void renderCount(@NonNull TextView view, @Nullable Integer count) {
        view.setText(getString(R.string.data_count_format, count == null ? 0 : count));
    }

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
}
