package com.skyanchor.bookkeeping.ui.sync;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.databinding.ActivityRecycleBinBinding;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.InsetsUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 回收站（V3.1 基线第 18/19 章）：展示已软删的交易、分类、账户、周期账单，
 * 支持逐条恢复。恢复会作为 UPSERT 重新入队同步，其他设备最终一致。
 * 保留策略：永久保留（V3.1 决策 3），无彻底删除入口。
 */
public class RecycleBinActivity extends AppCompatActivity {

    private static final int TAB_TRANSACTIONS = 0;
    private static final int TAB_CATEGORIES = 1;
    private static final int TAB_ACCOUNTS = 2;
    private static final int TAB_RECURRING = 3;

    private ActivityRecycleBinBinding binding;
    private RecycleBinViewModel viewModel;
    private RecycleBinAdapter adapter;

    private final List<TransactionEntity> deletedTransactions = new ArrayList<>();
    private final List<CategoryEntity> deletedCategories = new ArrayList<>();
    private final List<AccountEntity> deletedAccounts = new ArrayList<>();
    private final List<RecurringTransactionEntity> deletedRecurring = new ArrayList<>();
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("M月d日 HH:mm", Locale.getDefault());

    public static Intent newIntent(@NonNull Context context) {
        return new Intent(context, RecycleBinActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRecycleBinBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsUtil.applyTopAndHorizontalPadding(binding.recycleRoot);
        InsetsUtil.syncSystemBarAppearance(this);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        viewModel = new ViewModelProvider(this).get(RecycleBinViewModel.class);

        adapter = new RecycleBinAdapter(this::confirmRestore);
        binding.recycleList.setLayoutManager(new LinearLayoutManager(this));
        binding.recycleList.setAdapter(adapter);

        binding.recycleTabs.addTab(binding.recycleTabs.newTab()
                .setText(R.string.recycle_bin_tab_transactions));
        binding.recycleTabs.addTab(binding.recycleTabs.newTab()
                .setText(R.string.recycle_bin_tab_categories));
        binding.recycleTabs.addTab(binding.recycleTabs.newTab()
                .setText(R.string.recycle_bin_tab_accounts));
        binding.recycleTabs.addTab(binding.recycleTabs.newTab()
                .setText(R.string.recycle_bin_tab_recurring));
        binding.recycleTabs.addOnTabSelectedListener(
                new TabLayout.OnTabSelectedListener() {
                    @Override
                    public void onTabSelected(TabLayout.Tab tab) {
                        renderCurrentTab();
                    }

                    @Override
                    public void onTabUnselected(TabLayout.Tab tab) {
                    }

                    @Override
                    public void onTabReselected(TabLayout.Tab tab) {
                    }
                });

        observe();
    }

    private void observe() {
        viewModel.transactions().observe(this, list -> {
            deletedTransactions.clear();
            if (list != null) {
                deletedTransactions.addAll(list);
            }
            renderCurrentTab();
        });
        viewModel.categories().observe(this, list -> {
            deletedCategories.clear();
            if (list != null) {
                deletedCategories.addAll(list);
            }
            renderCurrentTab();
        });
        viewModel.accounts().observe(this, list -> {
            deletedAccounts.clear();
            if (list != null) {
                deletedAccounts.addAll(list);
            }
            renderCurrentTab();
        });
        viewModel.recurring().observe(this, list -> {
            deletedRecurring.clear();
            if (list != null) {
                deletedRecurring.addAll(list);
            }
            renderCurrentTab();
        });
        viewModel.restored().observe(this, restored -> {
            if (restored != null && restored) {
                Snackbar.make(binding.recycleRoot, R.string.recycle_bin_restored,
                        Snackbar.LENGTH_SHORT).show();
            }
        });
        viewModel.error().observe(this, message -> {
            if (message != null) {
                Snackbar.make(binding.recycleRoot, message, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void renderCurrentTab() {
        int tab = binding.recycleTabs.getSelectedTabPosition();
        List<RecycleBinAdapter.Row> rows = new ArrayList<>();
        if (tab == TAB_TRANSACTIONS) {
            for (TransactionEntity entity : deletedTransactions) {
                rows.add(new RecycleBinAdapter.Row(entity.id, transactionTitle(entity),
                        transactionMeta(entity)));
            }
        } else if (tab == TAB_CATEGORIES) {
            for (CategoryEntity entity : deletedCategories) {
                rows.add(new RecycleBinAdapter.Row(entity.id, entity.icon + " " + entity.name,
                        deletedMeta(entity.deletedAt)));
            }
        } else if (tab == TAB_ACCOUNTS) {
            for (AccountEntity entity : deletedAccounts) {
                rows.add(new RecycleBinAdapter.Row(entity.id, entity.name,
                        deletedMeta(entity.deletedAt)));
            }
        } else if (tab == TAB_RECURRING) {
            for (RecurringTransactionEntity entity : deletedRecurring) {
                rows.add(new RecycleBinAdapter.Row(entity.id, entity.name,
                        deletedMeta(entity.deletedAt)));
            }
        }
        adapter.submitList(rows);
        binding.emptyText.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private String transactionTitle(TransactionEntity entity) {
        // 同步层不解释业务口径：转账显示类型名，普通账单显示「类型 · 金额」
        if (entity.type == CategoryEntity.TYPE_TRANSFER) {
            return getString(R.string.edit_type_transfer);
        }
        return (entity.type == CategoryEntity.TYPE_INCOME
                ? getString(R.string.edit_type_income)
                : getString(R.string.edit_type_expense))
                + " · " + AmountUtil.format(entity.amount);
    }

    private String transactionMeta(TransactionEntity entity) {
        return deletedMeta(entity.deletedAt);
    }

    private String deletedMeta(Long deletedAt) {
        long time = deletedAt != null && deletedAt > 0
                ? deletedAt : System.currentTimeMillis();
        return getString(R.string.recycle_bin_deleted_at_format,
                dateFormat.format(new Date(time)));
    }

    private void confirmRestore(@NonNull RecycleBinAdapter.Row row) {
        int tab = binding.recycleTabs.getSelectedTabPosition();
        switch (tab) {
            case TAB_TRANSACTIONS:
                viewModel.restoreTransaction(row.localId);
                break;
            case TAB_CATEGORIES:
                viewModel.restoreCategory(row.localId);
                break;
            case TAB_ACCOUNTS:
                viewModel.restoreAccount(row.localId);
                break;
            case TAB_RECURRING:
                viewModel.restoreRecurring(row.localId);
                break;
            default:
                break;
        }
    }
}
