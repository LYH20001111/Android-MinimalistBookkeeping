package com.skyanchor.bookkeeping.ui.record;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.model.DailySummary;
import com.skyanchor.bookkeeping.databinding.ItemCalendarDayBinding;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.DateUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日历选择器网格适配器：每格显示日期数字，并在有流水时附加当日支出/收入摘要。
 *
 * <p>V1.1 目标 A：无流水的日期不显示金额（禁止出现 ±¥0.00）。整月网格一次性重建，
 * 因此这里用普通 {@link RecyclerView.Adapter} + {@code notifyDataSetChanged}，
 * 而不必引入 {@code DiffUtil}。
 */
public class CalendarDayAdapter extends RecyclerView.Adapter<CalendarDayAdapter.DayViewHolder> {

    /** 日期格点击回调。 */
    public interface OnDayClickListener {
        /** @param dayMillis 被点击日期当天 00:00 的 epoch millis */
        void onDayClick(long dayMillis);
    }

    /** 日历网格中的一个格子。 */
    public static final class DayCell {
        /** 当天 00:00 的 epoch millis。 */
        final long dayMillis;
        /** 日号，1-31。 */
        final int dayOfMonth;
        /** 是否属于当前展示的月份；相邻月的补齐格为 false，灰显。 */
        final boolean currentMonth;
        /** 是否为今天。 */
        final boolean today;
        /** 当日支出合计（分），无流水为 0。 */
        final long expense;
        /** 当日收入合计（分），无流水为 0。 */
        final long income;

        DayCell(long dayMillis, int dayOfMonth, boolean currentMonth, boolean today,
                long expense, long income) {
            this.dayMillis = dayMillis;
            this.dayOfMonth = dayOfMonth;
            this.currentMonth = currentMonth;
            this.today = today;
            this.expense = expense;
            this.income = income;
        }
    }

    private final List<DayCell> cells = new ArrayList<>();

    @Nullable
    private final OnDayClickListener listener;

    private long selectedDate;

    public CalendarDayAdapter(@Nullable OnDayClickListener listener) {
        this.listener = listener;
    }

    /**
     * 重建整月网格。
     *
     * @param year         展示月份所属年
     * @param month        展示月份，取值 1-12
     * @param summaries    当月每日收支摘要，可为 null（尚未加载完成）
     * @param selectedDate 当前选中日期当天 00:00 的 millis
     */
    public void submitMonth(int year, int month, @Nullable List<DailySummary> summaries,
                            long selectedDate) {
        this.selectedDate = selectedDate;
        cells.clear();

        Map<Long, DailySummary> summaryByDay = new HashMap<>();
        if (summaries != null) {
            for (DailySummary summary : summaries) {
                summaryByDay.put(summary.day, summary);
            }
        }

        long today = DateUtil.today();
        int daysInMonth = DateUtil.daysInMonth(year, month);
        long firstDay = DateUtil.dayMillisOf(year, month, 1);
        long lastDay = DateUtil.dayMillisOf(year, month, daysInMonth);

        // 当月 1 号前的空格数：周一为 0、周日为 6，用上月末尾几天补齐。
        int leading = DateUtil.mondayFirstIndex(DateUtil.calendar(firstDay));
        for (int i = leading - 1; i >= 0; i--) {
            long day = DateUtil.addDays(firstDay, -(i + 1));
            cells.add(newCell(day, false, today, summaryByDay));
        }
        for (int dayOfMonth = 1; dayOfMonth <= daysInMonth; dayOfMonth++) {
            long day = DateUtil.dayMillisOf(year, month, dayOfMonth);
            cells.add(newCell(day, true, today, summaryByDay));
        }
        // 补齐到整周（周日结束），保证网格行数为整数。
        int trailing = (7 - (cells.size() % 7)) % 7;
        for (int i = 1; i <= trailing; i++) {
            long day = DateUtil.addDays(lastDay, i);
            cells.add(newCell(day, false, today, summaryByDay));
        }

        notifyDataSetChanged();
    }

    /**
     * 仅更新选中日期并局部刷新受影响的两格，避免整表重绘。
     */
    public void setSelectedDate(long selectedDate) {
        if (this.selectedDate == selectedDate) {
            return;
        }
        long previous = this.selectedDate;
        this.selectedDate = selectedDate;
        for (int i = 0; i < cells.size(); i++) {
            long day = cells.get(i).dayMillis;
            if (day == previous || day == selectedDate) {
                notifyItemChanged(i);
            }
        }
    }

    private DayCell newCell(long day, boolean currentMonth, long today,
                            Map<Long, DailySummary> summaryByDay) {
        DailySummary summary = summaryByDay.get(day);
        long expense = summary != null ? summary.expense : 0L;
        long income = summary != null ? summary.income : 0L;
        return new DayCell(day, DateUtil.dayOfMonthOf(day), currentMonth, day == today,
                expense, income);
    }

    @Override
    public int getItemCount() {
        return cells.size();
    }

    @NonNull
    @Override
    public DayViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemCalendarDayBinding binding =
                ItemCalendarDayBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new DayViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull DayViewHolder holder, int position) {
        holder.bind(cells.get(position));
    }

    /** 单日格：日期数字（选中/今天态）+ 支出摘要 + 收入摘要。 */
    final class DayViewHolder extends RecyclerView.ViewHolder {

        private final ItemCalendarDayBinding binding;

        DayViewHolder(@NonNull ItemCalendarDayBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull DayCell cell) {
            boolean selected = cell.dayMillis == selectedDate;

            binding.dayNumber.setText(String.valueOf(cell.dayOfMonth));

            // 选中：primary 圆形填充；今天（未选中）：primary 圆形描边；其余：无背景。
            if (selected) {
                binding.dayNumber.setBackgroundResource(R.drawable.bg_day_selected);
            } else if (cell.today) {
                binding.dayNumber.setBackgroundResource(R.drawable.bg_day_today);
            } else {
                binding.dayNumber.setBackground(null);
            }

            int numberColor;
            if (selected) {
                numberColor = R.color.text_on_primary;
            } else if (cell.currentMonth) {
                numberColor = R.color.text_primary;
            } else {
                numberColor = R.color.text_tertiary;
            }
            binding.dayNumber.setTextColor(
                    ContextCompat.getColor(itemView.getContext(), numberColor));

            // 支出摘要：仅在有支出时显示，禁止显示 -¥0.00。
            if (cell.expense > 0L) {
                binding.expenseText.setVisibility(View.VISIBLE);
                binding.expenseText.setText(AmountUtil.formatSigned(cell.expense, false));
            } else {
                binding.expenseText.setVisibility(View.GONE);
            }

            // 收入摘要：仅在有收入时显示，禁止显示 +¥0.00。
            if (cell.income > 0L) {
                binding.incomeText.setVisibility(View.VISIBLE);
                binding.incomeText.setText(AmountUtil.formatSigned(cell.income, true));
            } else {
                binding.incomeText.setVisibility(View.GONE);
            }

            binding.dayCellRoot.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDayClick(cell.dayMillis);
                }
            });
        }
    }
}
