package com.skyanchor.bookkeeping.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.databinding.ItemRecurringRowBinding;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.DateUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 周期账单规则列表（V2 Phase 8）：类型 emoji + 名称 + 单期金额 + 频率摘要 + 编辑 / 删除。
 *
 * <p>频率摘要沿用「每天 / 每周 / 每月 / 每年」；间隔大于 1 时显示「每 N …」。
 * 停用的规则在第二行追加「已停用」徽标，行内动作与账户管理一致：编辑 + 删除。
 */
public class RecurringAdapter extends ListAdapter<RecurringTransactionEntity,
        RecurringAdapter.ViewHolder> {

    /** 行内动作回调，由 Activity 落到 ViewModel / 仓库层执行。 */
    public interface Listener {

        void onEdit(@NonNull RecurringTransactionEntity rule);

        void onDelete(@NonNull RecurringTransactionEntity rule);
    }

    private static final DiffUtil.ItemCallback<RecurringTransactionEntity> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<RecurringTransactionEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull RecurringTransactionEntity oldItem,
                                               @NonNull RecurringTransactionEntity newItem) {
                    return oldItem.id == newItem.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull RecurringTransactionEntity oldItem,
                                                  @NonNull RecurringTransactionEntity newItem) {
                    return oldItem.equals(newItem);
                }
            };

    @Nullable
    private final Listener listener;

    public RecurringAdapter(@Nullable Listener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemRecurringRowBinding binding = ItemRecurringRowBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    /** 行内第二行的频率摘要，如「每月 · 下次 2026年10月1日」。 */
    @NonNull
    public static String frequencySummary(@NonNull Context context,
                                          @NonNull RecurringTransactionEntity rule) {
        StringBuilder builder = new StringBuilder(frequencyText(context, rule));
        if (rule.isEnabled) {
            SimpleDateFormat format = new SimpleDateFormat(
                    context.getString(R.string.recurring_date_format), Locale.getDefault());
            builder.append(" · ")
                    .append(context.getString(R.string.recurring_start_date_hint))
                    .append(' ')
                    .append(format.format(new Date(rule.nextRunDate)));
        }
        return builder.toString();
    }

    /** 频率文案：间隔为 1 用「每天 / 每周 / 每月 / 每年」，否则「每 N …」。 */
    @NonNull
    public static String frequencyText(@NonNull Context context,
                                       @NonNull RecurringTransactionEntity rule) {
        int interval = Math.max(1, rule.interval);
        if (interval == 1) {
            switch (rule.frequency) {
                case RecurringTransactionEntity.FREQUENCY_WEEKLY:
                    return context.getString(R.string.recurring_frequency_weekly);
                case RecurringTransactionEntity.FREQUENCY_MONTHLY:
                    return context.getString(R.string.recurring_frequency_monthly);
                case RecurringTransactionEntity.FREQUENCY_YEARLY:
                    return context.getString(R.string.recurring_frequency_yearly);
                case RecurringTransactionEntity.FREQUENCY_DAILY:
                default:
                    return context.getString(R.string.recurring_frequency_daily);
            }
        }
        switch (rule.frequency) {
            case RecurringTransactionEntity.FREQUENCY_WEEKLY:
                return context.getString(R.string.recurring_freq_weekly_format, interval);
            case RecurringTransactionEntity.FREQUENCY_MONTHLY:
                return context.getString(R.string.recurring_freq_monthly_format, interval);
            case RecurringTransactionEntity.FREQUENCY_YEARLY:
                return context.getString(R.string.recurring_freq_yearly_format, interval);
            case RecurringTransactionEntity.FREQUENCY_DAILY:
            default:
                return context.getString(R.string.recurring_freq_daily_format, interval);
        }
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemRecurringRowBinding binding;
        @Nullable
        private final Listener listener;

        ViewHolder(@NonNull ItemRecurringRowBinding binding, @Nullable Listener listener) {
            super(binding.getRoot());
            this.binding = binding;
            this.listener = listener;
        }

        void bind(@NonNull RecurringTransactionEntity rule) {
            Context context = itemView.getContext();
            boolean income = rule.type == CategoryEntity.TYPE_INCOME;
            binding.rowIcon.setText(income ? "💰" : "💸");
            binding.rowName.setText(rule.name);
            binding.rowAmount.setText(AmountUtil.formatSigned(rule.amount, income));
            binding.rowAmount.setTextColor(ContextCompat.getColor(context,
                    income ? R.color.success : R.color.text_primary));
            binding.rowFrequency.setText(frequencySummary(context, rule));
            binding.rowDisabled.setVisibility(rule.isEnabled ? android.view.View.GONE
                    : android.view.View.VISIBLE);

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onEdit(rule);
                }
            });
            binding.rowEdit.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onEdit(rule);
                }
            });
            binding.rowDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDelete(rule);
                }
            });
        }
    }

    /** 供测试或扩展使用：规则的下一次记账日格式化。 */
    @NonNull
    public static String nextDateText(@NonNull Context context, long nextRunDate) {
        SimpleDateFormat format = new SimpleDateFormat(
                context.getString(R.string.recurring_date_format), Locale.getDefault());
        return format.format(new Date(DateUtil.startOfDay(nextRunDate)));
    }
}
