package com.skyanchor.bookkeeping.ui.mine;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.databinding.FragmentMineBinding;
import com.skyanchor.bookkeeping.databinding.ItemMenuRowBinding;
import com.skyanchor.bookkeeping.ui.account.AccountManageActivity;
import com.skyanchor.bookkeeping.ui.budget.BudgetSettingActivity;
import com.skyanchor.bookkeeping.ui.category.CategoryManageActivity;
import com.skyanchor.bookkeeping.ui.importexport.BackupActivity;
import com.skyanchor.bookkeeping.ui.importexport.DataExportActivity;
import com.skyanchor.bookkeeping.ui.importexport.DataImportActivity;
import com.skyanchor.bookkeeping.ui.importexport.RestoreActivity;
import com.skyanchor.bookkeeping.ui.recurring.RecurringManageActivity;
import com.skyanchor.bookkeeping.ui.settings.AboutActivity;
import com.skyanchor.bookkeeping.ui.settings.AppearanceActivity;
import com.skyanchor.bookkeeping.ui.settings.DataManageActivity;

/**
 * 「我的」页（V1 基线第 9 章，V2 扩展入口）。
 *
 * <p>全部为本地设置入口：分类管理 / 账户管理 / 预算设置 / 周期账单 / 外观设置 / 数据管理 /
 * 数据导出 / 数据导入 / 本地备份 / 本地恢复 / 关于 App。不含账号体系与云同步，
 * 因此这里没有任何登录、注册或同步入口。
 */
public class MineFragment extends Fragment {

    private FragmentMineBinding binding;

    public static MineFragment newInstance() {
        return new MineFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMineBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        bindRow(binding.menuCategory, R.drawable.ic_category,
                R.string.mine_category, R.string.mine_category_subtitle,
                v -> startActivity(new Intent(requireContext(), CategoryManageActivity.class)));

        bindRow(binding.menuAccount, R.drawable.ic_account,
                R.string.mine_account, R.string.mine_account_subtitle,
                v -> startActivity(new Intent(requireContext(), AccountManageActivity.class)));

        // 从「我的」进入时不指定月份，由预算页默认落到当前月。
        bindRow(binding.menuBudget, R.drawable.ic_budget,
                R.string.mine_budget, R.string.mine_budget_subtitle,
                v -> startActivity(BudgetSettingActivity.newIntent(requireContext())));

        bindRow(binding.menuRecurring, R.drawable.ic_recurring,
                R.string.mine_recurring, R.string.mine_recurring_subtitle,
                v -> startActivity(new Intent(requireContext(), RecurringManageActivity.class)));

        bindRow(binding.menuAppearance, R.drawable.ic_palette,
                R.string.mine_appearance, R.string.mine_appearance_subtitle,
                v -> startActivity(new Intent(requireContext(), AppearanceActivity.class)));

        bindRow(binding.menuData, R.drawable.ic_database,
                R.string.mine_data, R.string.mine_data_subtitle,
                v -> startActivity(new Intent(requireContext(), DataManageActivity.class)));

        bindRow(binding.menuExport, R.drawable.ic_export,
                R.string.mine_export, R.string.mine_export_subtitle,
                v -> startActivity(new Intent(requireContext(), DataExportActivity.class)));

        bindRow(binding.menuImport, R.drawable.ic_import,
                R.string.mine_import, R.string.mine_import_subtitle,
                v -> startActivity(new Intent(requireContext(), DataImportActivity.class)));

        bindRow(binding.menuBackup, R.drawable.ic_backup,
                R.string.mine_backup, R.string.mine_backup_subtitle,
                v -> startActivity(new Intent(requireContext(), BackupActivity.class)));

        bindRow(binding.menuRestore, R.drawable.ic_restore,
                R.string.mine_restore, R.string.mine_restore_subtitle,
                v -> startActivity(new Intent(requireContext(), RestoreActivity.class)));

        bindRow(binding.menuAbout, R.drawable.ic_info,
                R.string.mine_about, R.string.mine_about_subtitle,
                v -> startActivity(new Intent(requireContext(), AboutActivity.class)));
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    /** 菜单行是共享布局，图标与文案全部来自资源，避免在 XML 里重复五遍。 */
    private void bindRow(@NonNull ItemMenuRowBinding row, @DrawableRes int iconRes,
                         @StringRes int titleRes, @StringRes int subtitleRes,
                         @NonNull View.OnClickListener onClick) {
        row.menuIcon.setImageResource(iconRes);
        row.menuTitle.setText(titleRes);
        row.menuSubtitle.setText(subtitleRes);
        row.getRoot().setOnClickListener(onClick);
    }
}
