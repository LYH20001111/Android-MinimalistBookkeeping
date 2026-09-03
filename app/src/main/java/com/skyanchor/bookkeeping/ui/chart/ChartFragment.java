package com.skyanchor.bookkeeping.ui.chart;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.model.AccountBalance;
import com.skyanchor.bookkeeping.data.model.BudgetState;
import com.skyanchor.bookkeeping.data.model.CategoryBudgetState;
import com.skyanchor.bookkeeping.data.model.ChartUiState;
import com.skyanchor.bookkeeping.data.model.DateRange;
import com.skyanchor.bookkeeping.data.model.PeriodType;
import com.skyanchor.bookkeeping.databinding.FragmentChartBinding;
import com.skyanchor.bookkeeping.databinding.ItemAccountBalanceBinding;
import com.skyanchor.bookkeeping.databinding.ItemCategoryBudgetBinding;
import com.skyanchor.bookkeeping.ui.adapter.CategoryStatAdapter;
import com.skyanchor.bookkeeping.ui.budget.BudgetSettingActivity;
import com.skyanchor.bookkeeping.ui.record.TransactionEditActivity;
import com.skyanchor.bookkeeping.util.AccountTypes;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.DateLabels;
import com.skyanchor.bookkeeping.util.DateUtil;

import java.util.List;

/**
 * 图表页：按 V1 基线 7.1 的四层信息优先级渲染 —— 核心数字、周期对比、趋势、分类占比，
 * 月视图额外挂上预算卡片（第 8 章）。
 *
 * <p>本页不做任何统计计算，全部数据来自 {@link ChartViewModel} 派生的 {@link ChartUiState}，
 * 因此与记录页共用同一份 Room 查询，账单增删改后必然同源刷新（第 11 章「统计一致性」）。
 *
 * <p>Insets 已由 {@code MainActivity} 统一处理，Fragment 内不再重复消费。
 */
public class ChartFragment extends Fragment {

    private static final String TAG_PERIOD_PICKER = "chart_period_picker";

    private FragmentChartBinding binding;
    private ChartViewModel viewModel;
    private CategoryStatAdapter categoryStatAdapter;

    /** 同步周期开关到 UI 时置位，避免监听器把程序化勾选当成用户操作。 */
    private boolean updatingPeriodUi;

    /** 当前渲染的周期，供「去设置」跳转预算页时定位月份。 */
    @Nullable
    private DateRange currentRange;

    public static ChartFragment newInstance() {
        return new ChartFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentChartBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(ChartViewModel.class);

        categoryStatAdapter = new CategoryStatAdapter();
        binding.categoryStatList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.categoryStatList.setAdapter(categoryStatAdapter);

        // 两个自绘控件不硬编码任何中文文案，提示语由界面层注入。
        binding.lineChart.setEmptyText(getString(R.string.chart_trend_empty));
        binding.donutChart.setCenterCaption(getString(R.string.chart_total_expense));

        binding.periodGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked || updatingPeriodUi) {
                return;
            }
            viewModel.selectPeriod(periodOf(checkedId));
        });
        binding.prevButton.setOnClickListener(v -> viewModel.previousPeriod());
        binding.nextButton.setOnClickListener(v -> viewModel.nextPeriod());
        binding.budgetSetButton.setOnClickListener(v -> openBudgetSetting());

        // 点击周期导航中间区域打开周/月/年选择器，可直接跳到远期周期（V1.1 目标 C）。
        binding.periodCenter.setOnClickListener(v -> openPeriodPicker());

        // 空状态「记一笔」默认落在当前周期首日，便于补记；「回到当前周期」把锚点复位到今天。
        binding.chartEmpty.emptyAction.setOnClickListener(v -> startAddForCurrentPeriod());
        binding.chartEmpty.backToCurrentButton.setOnClickListener(v -> viewModel.backToCurrentPeriod());

        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        // 账户资金与周期无关，独立观察：账单或账户变化后总资产 / 各账户余额自动刷新。
        viewModel.getAccountBalances().observe(getViewLifecycleOwner(), this::renderAccounts);
    }

    @Override
    public void onDestroyView() {
        binding.categoryStatList.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private void render(@Nullable ChartUiState state) {
        if (state == null) {
            return;
        }
        currentRange = state.range;
        renderPeriod(state);
        renderSummary(state);
        renderChange(state);

        boolean noData = state.isEmpty();
        binding.chartScroll.setVisibility(noData ? View.GONE : View.VISIBLE);
        binding.chartEmpty.getRoot().setVisibility(noData ? View.VISIBLE : View.GONE);
        if (noData) {
            binding.budgetCard.setVisibility(View.GONE);
            return;
        }

        // 有账单但本周期没有任何支出（纯收入周期）时收起图表，避免出现空白块。
        boolean hasExpense = state.summary.expense > 0L;
        binding.trendCard.setVisibility(hasExpense ? View.VISIBLE : View.GONE);
        binding.categoryCard.setVisibility(hasExpense ? View.VISIBLE : View.GONE);
        binding.noExpenseHint.setVisibility(hasExpense ? View.GONE : View.VISIBLE);
        if (hasExpense) {
            binding.lineChart.setData(state.trend);
            binding.donutChart.setData(state.categoryStats);
            categoryStatAdapter.submitList(state.categoryStats);
        }

        renderBudget(state);
    }

    private void renderPeriod(@NonNull ChartUiState state) {
        updatingPeriodUi = true;
        binding.periodGroup.check(periodButtonId(state.range.type));
        updatingPeriodUi = false;

        binding.periodLabel.setText(state.label);
        // 标题（「Week 5」/「2026年9月」）只负责定位，副标题补上精确日期范围与笔数。
        String subtitle = DateLabels.periodSubtitle(requireContext(), state.range);
        binding.periodCount.setText(getString(R.string.chart_period_count_with_range,
                subtitle, state.summary.count));
        // 周期已经包含今天时不允许再往后翻，避免出现永远为空的未来周期。
        binding.nextButton.setEnabled(!state.range.containsToday());
    }

    private void renderSummary(@NonNull ChartUiState state) {
        binding.chartIncomeValue.setText(AmountUtil.format(state.summary.income));
        binding.chartExpenseValue.setText(AmountUtil.format(state.summary.expense));

        long balance = state.summary.balance();
        binding.chartBalanceValue.setText(AmountUtil.format(balance));
        // 结余为负（支出大于收入）时用 danger 语义色提示。
        binding.chartBalanceValue.setTextColor(colorOf(
                balance < 0L ? R.color.danger : R.color.text_primary));
    }

    /** 环比：上升 danger、下降 success、持平次要色、上期为 0 时只显示占位符。 */
    private void renderChange(@NonNull ChartUiState state) {
        String text;
        @ColorRes int colorRes;
        switch (state.changeDirection) {
            case ChartUiState.CHANGE_UP:
                text = getString(R.string.chart_change_up_format, state.changeValueText());
                colorRes = R.color.danger;
                break;
            case ChartUiState.CHANGE_DOWN:
                text = getString(R.string.chart_change_down_format, state.changeValueText());
                colorRes = R.color.success;
                break;
            case ChartUiState.CHANGE_FLAT:
                text = getString(R.string.chart_change_flat);
                colorRes = R.color.text_secondary;
                break;
            default:
                text = getString(R.string.placeholder_dash);
                colorRes = R.color.text_tertiary;
                break;
        }
        binding.chartChangeValue.setText(text);
        binding.chartChangeValue.setTextColor(colorOf(colorRes));
    }

    private void renderBudget(@NonNull ChartUiState state) {
        boolean monthView = state.range.type == PeriodType.MONTH;
        // 卡片显示条件：月视图且（设置了总预算 或 存在任一分类预算），否则整卡隐藏避免空块
        boolean hasCategoryBudgets = !state.categoryBudgetStates.isEmpty();
        binding.budgetCard.setVisibility(
                monthView && (state.budgetState.hasBudget || hasCategoryBudgets)
                        ? View.VISIBLE : View.GONE);
        if (!monthView) {
            return;
        }

        BudgetState budget = state.budgetState;
        binding.budgetSetGroup.setVisibility(budget.hasBudget ? View.VISIBLE : View.GONE);
        binding.budgetUnsetGroup.setVisibility(budget.hasBudget ? View.GONE : View.VISIBLE);
        binding.budgetStatusChip.setVisibility(budget.hasBudget ? View.VISIBLE : View.GONE);
        if (budget.hasBudget) {
            int statusColor = colorOf(statusColorOf(budget.status));
            binding.budgetStatusChip.setText(statusTextOf(budget.status));
            binding.budgetStatusChip.setTextColor(statusColor);
            Drawable chipBackground = binding.budgetStatusChip.getBackground();
            if (chipBackground != null) {
                // mutate 后不再与其他控件共享常量状态，浅色底不会污染别处的 bg_pill
                DrawableCompat.setTint(chipBackground.mutate(),
                        colorOf(statusBackgroundOf(budget.status)));
            }

            binding.budgetAmountValue.setText(AmountUtil.format(budget.budgetAmount));
            binding.budgetUsedValue.setText(AmountUtil.format(budget.used));
            binding.budgetRemainingValue.setText(AmountUtil.format(budget.remaining));
            binding.budgetUsageValue.setText(budget.percentText());

            binding.budgetProgress.setIndicatorColor(statusColor);
            // 进度条封顶 100%，超支部分由下方文字说明，否则刻度会被截断得毫无意义。
            binding.budgetProgress.setProgressCompat(
                    Math.min(budget.percentX10, getResources().getInteger(R.integer.percent_scale)),
                    false);

            boolean over = budget.status == BudgetState.STATUS_OVER;
            binding.budgetOverValue.setVisibility(over ? View.VISIBLE : View.GONE);
            if (over) {
                binding.budgetOverValue.setText(
                        getString(R.string.budget_over_format, AmountUtil.format(budget.overAmount)));
            }
        }

        renderCategoryBudgets(state.categoryBudgetStates);
    }

    /**
     * 分类预算完成度行（V2 Phase 6）：仅已设置预算的分类，按严重度排序。
     * 使用率与进度条复用与总预算完全同一套状态色，超支的分类自然置顶且显示 danger 色。
     */
    private void renderCategoryBudgets(@NonNull List<CategoryBudgetState> rows) {
        binding.budgetCategoryGroup.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        binding.budgetCategoryList.removeAllViews();
        if (rows.isEmpty()) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (CategoryBudgetState row : rows) {
            ItemCategoryBudgetBinding item = ItemCategoryBudgetBinding.inflate(
                    inflater, binding.budgetCategoryList, false);
            item.categoryIcon.setText(row.icon);
            item.categoryName.setText(row.name);
            int statusColor = colorOf(statusColorOf(row.state.status));
            item.categoryUsage.setText(row.state.percentText());
            item.categoryUsage.setTextColor(statusColor);
            item.categoryProgress.setIndicatorColor(statusColor);
            item.categoryProgress.setProgressCompat(
                    Math.min(row.state.percentX10,
                            getResources().getInteger(R.integer.percent_scale)), false);
            item.categoryCaption.setText(getString(R.string.budget_category_caption_format,
                    AmountUtil.format(row.budgetAmount), AmountUtil.format(row.used)));
            binding.budgetCategoryList.addView(item.getRoot());
        }
    }

    /**
     * 账户资金卡片：总资产 = 未归档账户余额之和，下方逐行列出各账户当前余额。
     * 余额可正可负（信用卡欠款为负），负数用 danger 语义色；无账户时整卡隐藏。
     */
    private void renderAccounts(@Nullable List<AccountBalance> accounts) {
        if (binding == null) {
            return;
        }
        if (accounts == null || accounts.isEmpty()) {
            binding.accountCard.setVisibility(View.GONE);
            return;
        }
        long total = 0L;
        for (AccountBalance account : accounts) {
            total += account.balance;
        }
        binding.accountCard.setVisibility(View.VISIBLE);
        binding.totalAssetsValue.setText(AmountUtil.format(total));
        binding.totalAssetsValue.setTextColor(colorOf(
                total < 0L ? R.color.danger : R.color.text_primary));

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        binding.accountBalanceList.removeAllViews();
        for (AccountBalance account : accounts) {
            ItemAccountBalanceBinding row = ItemAccountBalanceBinding.inflate(
                    inflater, binding.accountBalanceList, false);
            row.accountIcon.setText(AccountTypes.emoji(account.type));
            row.accountName.setText(account.name);
            row.accountBalance.setText(AmountUtil.format(account.balance));
            row.accountBalance.setTextColor(colorOf(
                    account.balance < 0L ? R.color.danger : R.color.text_primary));
            binding.accountBalanceList.addView(row.getRoot());
        }
    }

    private void openBudgetSetting() {
        DateRange range = currentRange;
        if (range == null) {
            ChartViewModel.PeriodQuery query = viewModel.getQuery();
            range = DateUtil.rangeOf(
                    query == null ? PeriodType.MONTH : query.type,
                    query == null ? DateUtil.today() : query.anchor);
        }
        BudgetSettingActivity.start(requireContext(), range.year, range.month);
    }

    /** 空状态「记一笔」：默认日期落在当前周期首日，便于补记该周期。 */
    private void startAddForCurrentPeriod() {
        DateRange range = currentRange;
        long day = range != null ? range.start : DateUtil.today();
        TransactionEditActivity.startAdd(requireContext(), day);
    }

    /** 打开与当前周期类型一致的周/月/年选择器（V1.1 目标 C）。 */
    private void openPeriodPicker() {
        ChartViewModel.PeriodQuery query = viewModel.getQuery();
        if (query == null) {
            return;
        }
        PeriodPickerDialog.newInstance(query.type, query.anchor)
                .show(getChildFragmentManager(), TAG_PERIOD_PICKER);
    }

    // ------------------------------------------------------------------
    // 周期与状态映射
    // ------------------------------------------------------------------

    @NonNull
    private static PeriodType periodOf(int checkedId) {
        if (checkedId == R.id.periodWeek) {
            return PeriodType.WEEK;
        }
        if (checkedId == R.id.periodYear) {
            return PeriodType.YEAR;
        }
        return PeriodType.MONTH;
    }

    private static int periodButtonId(@NonNull PeriodType type) {
        switch (type) {
            case WEEK:
                return R.id.periodWeek;
            case YEAR:
                return R.id.periodYear;
            default:
                return R.id.periodMonth;
        }
    }

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
        return ContextCompat.getColor(requireContext(), colorRes);
    }
}
