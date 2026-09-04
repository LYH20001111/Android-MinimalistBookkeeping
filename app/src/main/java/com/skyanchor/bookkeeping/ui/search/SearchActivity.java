package com.skyanchor.bookkeeping.ui.search;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.data.model.RecordListItem;
import com.skyanchor.bookkeeping.data.model.SearchFilter;
import com.skyanchor.bookkeeping.data.model.SearchResult;
import com.skyanchor.bookkeeping.databinding.ActivitySearchBinding;
import com.skyanchor.bookkeeping.ui.adapter.TransactionListAdapter;
import com.skyanchor.bookkeeping.ui.record.TransactionEditActivity;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.DateLabels;
import com.skyanchor.bookkeeping.util.DateUtil;
import com.skyanchor.bookkeeping.util.DayLabelProvider;
import com.skyanchor.bookkeeping.util.InsetsUtil;
import com.skyanchor.bookkeeping.util.StatisticsCalculator;

import java.util.Collections;
import java.util.List;

/**
 * 搜索 + 筛选页（V2 新增，开发计划 Phase 4）。
 *
 * <p>关键词命中备注 / 分类名 / 账户名；类型 Chips（不选=全部）、分类 / 账户图标选择器
 * （首项=全部，V2.1 Phase 1 改为图标网格 / 图标列表弹窗）、金额区间共同收窄结果。
 * 结果复用 {@link TransactionListAdapter} 按日分组展示，顶部合计
 * 「共 N 笔 · 支出 · 收入」与列表同源（转账计入笔数、不计收支）。
 *
 * <p>所有筛选状态存在 {@link SearchViewModel} 的 {@link SearchFilter} 里，旋转 / 重建后由
 * {@link #restoreWidgets} 一次性回填控件；控件是唯一的用户输入源，故不反向观察 filter，避免回环。
 */
public class SearchActivity extends AppCompatActivity {

    private ActivitySearchBinding binding;
    private SearchViewModel viewModel;
    private TransactionListAdapter adapter;
    private DayLabelProvider dayLabels;

    /** 分类候选（支出 + 收入），供选择器弹窗使用。 */
    @Nullable
    private List<CategoryEntity> categoryList = null;

    /** 账户候选（含已归档），供选择器弹窗使用。 */
    @Nullable
    private List<AccountEntity> accountList = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySearchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applySystemBarsPadding(binding.searchRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        viewModel = new ViewModelProvider(this).get(SearchViewModel.class);
        dayLabels = DateLabels.dayLabels(this, DateUtil.today());
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new TransactionListAdapter(new TransactionListAdapter.Listener() {
            @Override
            public void onTransactionClick(@NonNull TransactionItem item) {
                TransactionEditActivity.startEdit(SearchActivity.this, item.id);
            }

            @Override
            public void onTransactionLongClick(@NonNull TransactionItem item) {
                showDeleteDialog(item);
            }
        });
        binding.searchList.setLayoutManager(new LinearLayoutManager(this));
        binding.searchList.setAdapter(adapter);

        // 先按已有筛选回填控件，再挂监听，避免回填触发监听造成无谓更新。
        restoreWidgets();
        wireListeners();

        viewModel.getCategories().observe(this, this::onCategoriesChanged);
        viewModel.getAccounts().observe(this, this::onAccountsChanged);
        viewModel.getResults().observe(this, this::render);
    }

    @Override
    protected void onDestroy() {
        binding.searchList.setAdapter(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 控件 ↔ 筛选状态
    // ------------------------------------------------------------------

    /** 旋转 / 重建后把 ViewModel 里存活的筛选条件回填到控件（此时监听尚未挂载）。 */
    private void restoreWidgets() {
        SearchFilter filter = currentFilter();
        binding.searchInput.setText(filter.keyword);
        // 「不限类型」在 UI 上表现为无 chip 选中，故三选全开时反而全部不勾。
        boolean allTypes =
                filter.includeExpense && filter.includeIncome && filter.includeTransfer;
        binding.chipExpense.setChecked(!allTypes && filter.includeExpense);
        binding.chipIncome.setChecked(!allTypes && filter.includeIncome);
        binding.chipTransfer.setChecked(!allTypes && filter.includeTransfer);
        if (filter.minAmount != SearchFilter.NO_MIN_AMOUNT) {
            binding.minAmountInput.setText(AmountUtil.toInputText(filter.minAmount));
        }
        if (filter.maxAmount != SearchFilter.NO_MAX_AMOUNT) {
            binding.maxAmountInput.setText(AmountUtil.toInputText(filter.maxAmount));
        }
        // 分类 / 账户下拉的文本在候选数据到达时按 filter 回填（见 onCategoriesChanged / onAccountsChanged）。
    }

    private void wireListeners() {
        binding.searchInput.addTextChangedListener(new AfterTextChangedWatcher(
                s -> viewModel.setKeyword(s == null ? null : s.toString())));

        binding.typeChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            boolean expense = checkedIds.contains(R.id.chipExpense);
            boolean income = checkedIds.contains(R.id.chipIncome);
            boolean transfer = checkedIds.contains(R.id.chipTransfer);
            // 无 chip 选中 = 不限类型（全 true）；否则只保留选中的类型。
            boolean none = !expense && !income && !transfer;
            viewModel.setTypes(none || expense, none || income, none || transfer);
        });

        TextWatcher amountWatcher = new AfterTextChangedWatcher(s -> applyAmountRange());
        binding.minAmountInput.addTextChangedListener(amountWatcher);
        binding.maxAmountInput.addTextChangedListener(amountWatcher);

        // 分类 / 账户为只读输入框，点击弹出图标选择器（V2.1 Phase 1）；
        // 尾部图标是 custom 模式（不能用 ExposedDropdownMenu 样式——它强制要求
        // AutoCompleteTextView 子控件，V2.1 改弹窗后已不用），同样要接住点击避免死区。
        binding.categoryInput.setOnClickListener(v -> showCategoryPicker());
        binding.categoryLayout.setEndIconOnClickListener(v -> showCategoryPicker());
        binding.accountInput.setOnClickListener(v -> showAccountPicker());
        binding.accountLayout.setEndIconOnClickListener(v -> showAccountPicker());

        binding.resetButton.setOnClickListener(v -> resetAll());
    }

    /** 清空筛选：先复位状态，再清空控件（控件监听触发的是等价更新，被去重挡下）。 */
    private void resetAll() {
        viewModel.reset();
        binding.searchInput.setText("");
        binding.typeChipGroup.clearCheck();
        binding.minAmountInput.setText("");
        binding.maxAmountInput.setText("");
        binding.categoryInput.setText(R.string.search_all_categories);
        binding.accountInput.setText(R.string.search_all_accounts);
    }

    private void applyAmountRange() {
        long min = parseAmountOr(binding.minAmountInput.getText(), SearchFilter.NO_MIN_AMOUNT);
        long max = parseAmountOr(binding.maxAmountInput.getText(), SearchFilter.NO_MAX_AMOUNT);
        viewModel.setAmountRange(min, max);
    }

    /** 金额输入解析：空或非法回落到给定的「不限」哨兵，避免半截输入误伤结果。 */
    private static long parseAmountOr(@Nullable Editable text, long fallback) {
        if (text == null) {
            return fallback;
        }
        String value = text.toString().trim();
        if (value.isEmpty()) {
            return fallback;
        }
        long cents = AmountUtil.parseToCents(value);
        return cents == AmountUtil.INVALID ? fallback : cents;
    }

    private void onCategoriesChanged(@Nullable List<CategoryEntity> categories) {
        categoryList = categories == null ? Collections.<CategoryEntity>emptyList() : categories;
        long selected = currentFilter().categoryId;
        String selectedLabel = getString(R.string.search_all_categories);
        for (CategoryEntity category : categoryList) {
            if (category.id == selected) {
                selectedLabel = category.name;
                break;
            }
        }
        binding.categoryInput.setText(selectedLabel);
    }

    private void onAccountsChanged(@Nullable List<AccountEntity> accounts) {
        accountList = accounts == null ? Collections.<AccountEntity>emptyList() : accounts;
        long selected = currentFilter().accountId;
        String selectedLabel = getString(R.string.search_all_accounts);
        for (AccountEntity account : accountList) {
            if (account.id == selected) {
                selectedLabel = account.name;
                break;
            }
        }
        binding.accountInput.setText(selectedLabel);
    }

    // ------------------------------------------------------------------
    // V2.1 图标选择器（分类：图标网格；账户：图标 + 类型列表）
    // ------------------------------------------------------------------

    private void showCategoryPicker() {
        List<CategoryEntity> categories =
                categoryList == null ? Collections.<CategoryEntity>emptyList() : categoryList;
        CategoryPickerDialog.show(this, categories, currentFilter().categoryId,
                RecentFilterStore.recentIds(this, RecentFilterStore.SCOPE_CATEGORY),
                category -> {
                    viewModel.setCategoryId(category.id);
                    RecentFilterStore.record(this, RecentFilterStore.SCOPE_CATEGORY,
                            category.id);
                    binding.categoryInput.setText(category.name);
                });
    }

    private void showAccountPicker() {
        List<AccountEntity> accounts =
                accountList == null ? Collections.<AccountEntity>emptyList() : accountList;
        AccountPickerDialog.show(this, accounts, currentFilter().accountId,
                RecentFilterStore.recentIds(this, RecentFilterStore.SCOPE_ACCOUNT),
                account -> {
                    viewModel.setAccountId(account.id);
                    RecentFilterStore.record(this, RecentFilterStore.SCOPE_ACCOUNT,
                            account.id);
                    binding.accountInput.setText(account.name);
                });
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private void render(@Nullable SearchResult result) {
        if (result == null) {
            return;
        }
        List<RecordListItem> rows = StatisticsCalculator.groupByDay(result.items, dayLabels);
        adapter.submitList(rows);
        boolean empty = result.isEmpty();
        binding.searchList.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.searchEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.searchSummary.setText(getString(R.string.search_summary_format,
                result.summary.count,
                AmountUtil.format(result.summary.expense),
                AmountUtil.format(result.summary.income)));
    }

    // ------------------------------------------------------------------
    // 删除（复用记录页的二次确认；删除后结果 LiveData 自动刷新）
    // ------------------------------------------------------------------

    private void showDeleteDialog(@NonNull TransactionItem item) {
        String description = item.isTransfer()
                ? getString(R.string.record_transfer_format,
                        item.displayAccountName(), item.displayTransferAccountName())
                : item.displayIcon() + item.displayName();
        String amount = item.isTransfer()
                ? AmountUtil.format(item.amount)
                : AmountUtil.formatSigned(item.amount, item.isIncome());
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.record_delete_title)
                .setMessage(getString(R.string.record_delete_message, description, amount))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        viewModel.deleteTransaction(item.id, deleted -> onDeleted()))
                .show();
    }

    private void onDeleted() {
        if (isFinishing() || isDestroyed() || binding == null) {
            return;
        }
        Snackbar.make(binding.searchRoot, R.string.record_deleted, Snackbar.LENGTH_SHORT).show();
    }

    @NonNull
    private SearchFilter currentFilter() {
        SearchFilter filter = viewModel.getFilter().getValue();
        return filter == null ? SearchFilter.all() : filter;
    }

    /** 只关心 afterTextChanged 的轻量 TextWatcher，省去三处空实现重复。 */
    private interface AfterTextAction {
        void run(@Nullable Editable text);
    }

    private static final class AfterTextChangedWatcher implements TextWatcher {

        private final AfterTextAction action;

        AfterTextChangedWatcher(AfterTextAction action) {
            this.action = action;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            action.run(s);
        }
    }
}
