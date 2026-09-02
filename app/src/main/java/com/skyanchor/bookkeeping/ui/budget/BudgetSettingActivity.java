package com.skyanchor.bookkeeping.ui.budget;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.model.BudgetState;
import com.skyanchor.bookkeeping.data.model.DateRange;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.databinding.ActivityBudgetSettingBinding;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.DateLabels;
import com.skyanchor.bookkeeping.util.DateUtil;
import com.skyanchor.bookkeeping.util.InsetsUtil;

/**
 * 预算设置（V1 基线第 8 章）。
 *
 * <p>每个月只存一个总预算，不分分类预算；预算只按支出计算，不把收入算作预算消耗。
 * 执行情况与图表页预算卡片共用 {@link BudgetState}，因此两处的阈值与状态色必然一致。
 *
 * <p>清空金额后保存等价于删除该月预算。
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
}
