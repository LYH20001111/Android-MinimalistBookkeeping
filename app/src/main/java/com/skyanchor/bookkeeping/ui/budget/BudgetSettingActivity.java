package com.skyanchor.bookkeeping.ui.budget;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.Transformations;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.data.model.BudgetState;
import com.skyanchor.bookkeeping.data.model.CategoryBudgetState;
import com.skyanchor.bookkeeping.data.model.CategoryStat;
import com.skyanchor.bookkeeping.data.model.DateRange;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.databinding.ActivityBudgetSettingBinding;
import com.skyanchor.bookkeeping.databinding.DialogCategoryBudgetEditBinding;
import com.skyanchor.bookkeeping.databinding.ItemCategoryBudgetBinding;
import com.skyanchor.bookkeeping.domain.budget.CalculateBudgetUseCase;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.DateLabels;
import com.skyanchor.bookkeeping.util.DateUtil;
import com.skyanchor.bookkeeping.util.InsetsUtil;
import com.skyanchor.bookkeeping.util.StatisticsCalculator;

import java.util.Collections;
import java.util.List;

/**
 * 预算设置（V1 基线第 8 章；V2 Phase 6 扩展为「总预算 + 分类预算」）。
 *
 * <p>每个月保存一个总预算（category_id = 0 哨兵）与任意多个分类预算
 * （category_id &gt;= 1，依赖 (year, month, category_id) 唯一索引）。预算只按支出计算，
 * 不把收入算作预算消耗；分类预算与总预算共用 {@link BudgetState} 的阈值与状态色，
 * 且分类预算不反向限制记账，仅提醒 / 分析。
 *
 * <p>清空金额后保存等价于删除对应预算（总预算与分类预算同规则）。
 */
public class BudgetSettingActivity extends AppCompatActivity {

    /** 目标年份；缺省为当前年。 */
    public static final String EXTRA_YEAR = "extra_year";

    /** 目标月份，取值 1-12；缺省为当前月。 */
    public static final String EXTRA_MONTH = "extra_month";

    private static final String STATE_YEAR = "state_year";
    private static final String STATE_MONTH = "state_month";
    private static final String STATE_INPUT = "state_input";

    /** 当前选中的年月，作为两条 switchMap 的查询源。 */
    private static final class Month {

        final int year;
        final int month;

        Month(int year, int month) {
            this.year = year;
            this.month = month;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Month)) {
                return false;
            }
            Month other = (Month) o;
            return year == other.year && month == other.month;
        }

        @Override
        public int hashCode() {
            return year * 12 + month;
        }
    }

    private ActivityBudgetSettingBinding binding;
    private BookkeepingRepository repository;

    private final MutableLiveData<Month> selectedMonth = new MutableLiveData<>();

    private long budgetCents;
    private long usedCents;

    /** 旋转前尚未保存的输入，恢复后优先于数据库回填，避免丢掉用户正在改的数字。 */
    @Nullable
    private String pendingInput;

    /** 已经回填过金额的月份，防止 LiveData 再次到达时覆盖用户输入。 */
    @Nullable
    private Month filledMonth;

    @NonNull
    public static Intent newIntent(@NonNull Context context) {
        long today = DateUtil.today();
        return newIntent(context, DateUtil.yearOf(today), DateUtil.monthOf(today));
    }

    @NonNull
    public static Intent newIntent(@NonNull Context context, int year, int month) {
        Intent intent = new Intent(context, BudgetSettingActivity.class);
        intent.putExtra(EXTRA_YEAR, year);
        intent.putExtra(EXTRA_MONTH, month);
        return intent;
    }

    /** 从图表页「去设置」进入时定位到当前展示的月份。 */
    public static void start(@NonNull Context context, int year, int month) {
        context.startActivity(newIntent(context, year, month));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityBudgetSettingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applyTopAndHorizontalPadding(binding.budgetRoot);
        InsetsUtil.applyImeBottomPadding(binding.budgetScroll);
        InsetsUtil.syncSystemBarAppearance(this);

        repository = BookkeepingApp.get(this).getRepository();
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        binding.prevMonthButton.setOnClickListener(v -> shiftMonth(-1));
        binding.nextMonthButton.setOnClickListener(v -> shiftMonth(1));
        binding.saveBudgetButton.setOnClickListener(v -> save());
        binding.budgetAmountInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                binding.budgetAmountLayout.setError(null);
            }
        });

        LiveData<BudgetEntity> budget =
                Transformations.switchMap(selectedMonth,
                        m -> repository.observeBudget(m.year, m.month));
        budget.observe(this, this::onBudgetChanged);

        LiveData<Long> used = Transformations.switchMap(selectedMonth, m -> {
            DateRange range = DateUtil.ofMonth(m.year, m.month);
            return repository.observeSum(CategoryEntity.TYPE_EXPENSE, range.start, range.end);
        });
        used.observe(this, this::onUsedChanged);

        // 分类预算行：交易 / 支出分类 / 分类预算三路同源合并，任一变化重算同一份列表（V2 Phase 6）
        LiveData<List<CategoryBudgetState>> categoryRows =
                Transformations.switchMap(selectedMonth, this::observeCategoryBudgetRows);
        categoryRows.observe(this, this::renderCategoryRows);

        selectedMonth.observe(this, this::onMonthChanged);
        selectedMonth.setValue(resolveMonth(savedInstanceState));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        Month month = selectedMonth.getValue();
        if (month != null) {
            outState.putInt(STATE_YEAR, month.year);
            outState.putInt(STATE_MONTH, month.month);
        }
        Editable editable = binding.budgetAmountInput.getText();
        outState.putString(STATE_INPUT, editable == null ? "" : editable.toString());
    }

    @NonNull
    private Month resolveMonth(@Nullable Bundle savedInstanceState) {
        long today = DateUtil.today();
        int defaultYear = DateUtil.yearOf(today);
        int defaultMonth = DateUtil.monthOf(today);
        if (savedInstanceState != null) {
            pendingInput = savedInstanceState.getString(STATE_INPUT);
            return new Month(savedInstanceState.getInt(STATE_YEAR, defaultYear),
                    savedInstanceState.getInt(STATE_MONTH, defaultMonth));
        }
        return new Month(getIntent().getIntExtra(EXTRA_YEAR, defaultYear),
                getIntent().getIntExtra(EXTRA_MONTH, defaultMonth));
    }

    private void onMonthChanged(@NonNull Month month) {
        binding.monthLabel.setText(DateLabels.monthTitle(this, month.year, month.month));
        // 基线 8.3：预算只能设到当前月，未来月份既没有账单也没有执行意义
        binding.nextMonthButton.setEnabled(!DateUtil.isCurrentMonth(month.year, month.month));

        if (month.equals(filledMonth)) {
            return;
        }
        filledMonth = month;
        if (pendingInput != null) {
            binding.budgetAmountInput.setText(pendingInput);
            pendingInput = null;
            return;
        }
        repository.loadBudget(month.year, month.month, existing -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            binding.budgetAmountInput.setText(existing == null
                    ? "" : AmountUtil.toInputText(existing.amount));
            binding.budgetAmountLayout.setError(null);
        });
    }

    private void shiftMonth(int delta) {
        Month current = selectedMonth.getValue();
        if (current == null) {
            return;
        }
        DateRange target = DateUtil.shift(DateUtil.ofMonth(current.year, current.month), delta);
        if (DateUtil.isFutureMonth(target.year, target.month)) {
            return;
        }
        selectedMonth.setValue(new Month(target.year, target.month));
    }

    private void onBudgetChanged(@Nullable BudgetEntity budget) {
        budgetCents = budget == null ? 0L : budget.amount;
        renderStatus();
    }

    private void onUsedChanged(@Nullable Long sum) {
        usedCents = sum == null ? 0L : sum;
        renderStatus();
    }

    /** 已用 / 剩余 / 使用率 / 进度条 / 状态色，与图表页预算卡片完全同规则。 */
    private void renderStatus() {
        BudgetState state = BudgetState.of(budgetCents, usedCents);
        // BudgetState.NOT_SET 的 used 恒为 0，未设置预算时也要如实展示已消费
        binding.statusUsedValue.setText(AmountUtil.format(usedCents));

        if (!state.hasBudget) {
            binding.statusAmountValue.setText(AmountUtil.format(0L));
            binding.statusRemainingValue.setText(R.string.placeholder_dash);
            binding.statusUsageValue.setText(R.string.placeholder_dash);
            binding.statusChip.setVisibility(View.GONE);
            binding.statusProgress.setIndicatorColor(colorOf(R.color.primary));
            binding.statusProgress.setProgressCompat(0, false);
            return;
        }

        int statusColor = colorOf(statusColorOf(state.status));
        binding.statusAmountValue.setText(AmountUtil.format(state.budgetAmount));
        binding.statusRemainingValue.setText(AmountUtil.format(state.remaining));
        binding.statusUsageValue.setText(state.percentText());

        binding.statusChip.setVisibility(View.VISIBLE);
        binding.statusChip.setText(statusTextOf(state.status));
        binding.statusChip.setTextColor(statusColor);
        Drawable chipBackground = binding.statusChip.getBackground();
        if (chipBackground != null) {
            DrawableCompat.setTint(chipBackground.mutate(), colorOf(statusBackgroundOf(state.status)));
        }

        binding.statusProgress.setIndicatorColor(statusColor);
        binding.statusProgress.setProgressCompat(
                Math.min(state.percentX10, getResources().getInteger(R.integer.percent_scale)),
                false);
    }

    private void save() {
        Month month = selectedMonth.getValue();
        if (month == null) {
            return;
        }
        Editable editable = binding.budgetAmountInput.getText();
        String text = editable == null ? "" : editable.toString().trim();
        // 空串按 0 处理，语义是「删除该月预算」，不是输入错误
        long cents = text.isEmpty() ? 0L : AmountUtil.parseToCents(text);
        if (cents == AmountUtil.INVALID) {
            binding.budgetAmountLayout.setError(getString(R.string.budget_error_amount));
            binding.budgetAmountInput.requestFocus();
            return;
        }
        binding.saveBudgetButton.setEnabled(false);
        repository.saveBudget(month.year, month.month, cents, saved -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            binding.saveBudgetButton.setEnabled(true);
            if (saved == null || !saved) {
                return;
            }
            Toast.makeText(this, cents > 0L ? R.string.budget_saved : R.string.budget_removed,
                    Toast.LENGTH_SHORT).show();
        });
    }

    // ------------------------------------------------------------------
    // 分类预算（V2 Phase 6）
    // ------------------------------------------------------------------

    /** 分类预算行的三个数据源当前值，全部到达后才产出列表，避免残缺快照。 */
    private static final class CategoryRowSources {
        @Nullable
        List<TransactionItem> items;
        @Nullable
        List<CategoryEntity> categories;
        @Nullable
        List<BudgetEntity> budgets;
    }

    /**
     * 把当月的交易、支出分类与分类预算合并成分类预算行 LiveData。
     *
     * <p>交易用于按分类汇总本月支出（复用 {@link StatisticsCalculator#categoryBreakdown}），
     * 三路数据与总预算、图表页共用同一 Room 数据源，账单 / 预算变化后自动刷新。
     */
    @NonNull
    private LiveData<List<CategoryBudgetState>> observeCategoryBudgetRows(@NonNull Month month) {
        final DateRange range = DateUtil.ofMonth(month.year, month.month);
        final MediatorLiveData<List<CategoryBudgetState>> result = new MediatorLiveData<>();
        final CategoryRowSources sources = new CategoryRowSources();

        Observer<List<TransactionItem>> itemsObserver = items -> {
            sources.items = items;
            result.setValue(buildCategoryRows(range, sources));
        };
        Observer<List<CategoryEntity>> categoriesObserver = categories -> {
            sources.categories = categories;
            result.setValue(buildCategoryRows(range, sources));
        };
        Observer<List<BudgetEntity>> budgetsObserver = budgets -> {
            sources.budgets = budgets;
            result.setValue(buildCategoryRows(range, sources));
        };

        result.addSource(repository.observeTransactionsBetween(range.start, range.end), itemsObserver);
        result.addSource(repository.observeCategories(CategoryEntity.TYPE_EXPENSE), categoriesObserver);
        result.addSource(repository.observeCategoryBudgets(month.year, month.month), budgetsObserver);
        return result;
    }

    /** 三路数据到齐后组装分类预算行（含未设置预算的分类，供展示已消费与引导设置）。 */
    @NonNull
    private static List<CategoryBudgetState> buildCategoryRows(@NonNull DateRange range,
                                                               @NonNull CategoryRowSources sources) {
        if (sources.items == null || sources.categories == null || sources.budgets == null) {
            return Collections.emptyList();
        }
        List<CategoryStat> stats = StatisticsCalculator.categoryBreakdown(sources.items,
                CategoryEntity.TYPE_EXPENSE, range.start, range.end);
        return CalculateBudgetUseCase.assembleForManage(sources.categories, sources.budgets, stats);
    }

    /**
     * 渲染分类预算行：每分类一行（预算额 / 已用 / 进度 / 状态色），点击行弹出编辑弹窗。
     * 行为可点击（ripple 背景只在设置页注入，图表页复用同一布局但只读）。
     */
    private void renderCategoryRows(@Nullable List<CategoryBudgetState> rows) {
        List<CategoryBudgetState> safe = rows == null ? Collections.<CategoryBudgetState>emptyList() : rows;
        binding.categoryBudgetCard.setVisibility(safe.isEmpty() ? View.GONE : View.VISIBLE);
        binding.categoryBudgetList.removeAllViews();
        if (safe.isEmpty()) {
            return;
        }

        TypedValue background = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true);
        int maxProgress = getResources().getInteger(R.integer.percent_scale);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (CategoryBudgetState row : safe) {
            ItemCategoryBudgetBinding item = ItemCategoryBudgetBinding.inflate(
                    inflater, binding.categoryBudgetList, false);
            item.categoryIcon.setText(row.icon);
            item.categoryName.setText(row.name);

            int statusColor = colorOf(statusColorOf(row.state.status));
            if (row.hasBudget()) {
                item.categoryUsage.setText(row.state.percentText());
                item.categoryUsage.setTextColor(statusColor);
                item.categoryCaption.setText(getString(R.string.budget_category_caption_format,
                        AmountUtil.format(row.budgetAmount), AmountUtil.format(row.used)));
            } else {
                item.categoryUsage.setText(R.string.budget_category_unset);
                item.categoryUsage.setTextColor(colorOf(R.color.text_tertiary));
                item.categoryCaption.setText(getString(R.string.budget_category_caption_unset_format,
                        AmountUtil.format(row.used)));
            }
            item.categoryProgress.setIndicatorColor(statusColor);
            item.categoryProgress.setProgressCompat(
                    Math.min(row.state.percentX10, maxProgress), false);

            item.getRoot().setBackgroundResource(background.resourceId);
            item.getRoot().setOnClickListener(v -> showCategoryBudgetDialog(row));
            binding.categoryBudgetList.addView(item.getRoot());
        }
    }

    /**
     * 分类预算编辑弹窗：预填当前预算，清空后保存即删除（与总预算同规则）。
     * 确定按钮手动关闭，非法输入时就地显示错误、留在弹窗内。
     */
    private void showCategoryBudgetDialog(@NonNull CategoryBudgetState row) {
        Month month = selectedMonth.getValue();
        if (month == null) {
            return;
        }
        DialogCategoryBudgetEditBinding dialogBinding =
                DialogCategoryBudgetEditBinding.inflate(getLayoutInflater());
        if (row.hasBudget()) {
            dialogBinding.amountInput.setText(AmountUtil.toInputText(row.budgetAmount));
        }
        dialogBinding.amountInput.addTextChangedListener(
                new ClearErrorWatcher(dialogBinding.amountLayout));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.category_budget_edit_title, row.name))
                .setView(dialogBinding.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Editable editable = dialogBinding.amountInput.getText();
            String text = editable == null ? "" : editable.toString().trim();
            // 空串按 0 处理，语义是「删除该分类预算」，不是输入错误
            long cents = text.isEmpty() ? 0L : AmountUtil.parseToCents(text);
            if (cents == AmountUtil.INVALID) {
                dialogBinding.amountLayout.setError(getString(R.string.category_budget_error_amount));
                return;
            }
            repository.saveBudget(month.year, month.month, (int) row.categoryId, cents, saved -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (saved == null || !saved) {
                    return;
                }
                Toast.makeText(this, cents > 0L
                                ? R.string.category_budget_saved
                                : R.string.category_budget_removed,
                        Toast.LENGTH_SHORT).show();
            });
            dialog.dismiss();
        });
    }

    // ------------------------------------------------------------------
    // 状态映射（与 ChartFragment 保持同一套语义色）
    // ------------------------------------------------------------------

    @ColorRes
    private static int statusColorOf(int status) {
        switch (status) {
            case BudgetState.STATUS_OVER:
                return R.color.danger;
            case BudgetState.STATUS_WARNING:
                return R.color.warning;
            default:
                return R.color.primary;
        }
    }

    @ColorRes
    private static int statusBackgroundOf(int status) {
        switch (status) {
            case BudgetState.STATUS_OVER:
                return R.color.danger_light;
            case BudgetState.STATUS_WARNING:
                return R.color.warning_light;
            default:
                return R.color.primary_light;
        }
    }

    private static int statusTextOf(int status) {
        switch (status) {
            case BudgetState.STATUS_OVER:
                return R.string.budget_status_over;
            case BudgetState.STATUS_WARNING:
                return R.string.budget_status_warning;
            default:
                return R.string.budget_status_normal;
        }
    }

    private int colorOf(@ColorRes int colorRes) {
        return ContextCompat.getColor(this, colorRes);
    }

    /** 输入变化即清除弹窗输入框的错误提示，与账户编辑弹窗的手感一致。 */
    private static final class ClearErrorWatcher implements TextWatcher {

        private final com.google.android.material.textfield.TextInputLayout layout;

        ClearErrorWatcher(com.google.android.material.textfield.TextInputLayout layout) {
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
