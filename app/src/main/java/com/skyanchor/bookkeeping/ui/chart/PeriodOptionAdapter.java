package com.skyanchor.bookkeeping.ui.chart;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.model.PeriodOption;
import com.skyanchor.bookkeeping.databinding.ItemPeriodOptionBinding;

/**
 * 周期选择器网格适配器（V1.1 目标 C）：渲染周/月/年选项卡片。
 *
 * <p>选项由 {@code ChartViewModel} 从「每天笔数」聚合而来，已按「最近周期在前」排序，
 * 这里只负责渲染，并把当前所在周期高亮（primary 边框 + primary_light 背景）。
 *
 * <p>选中态在弹窗生命周期内是固定的（点开即定位当前周期，点击选项后立即关闭），
 * 因此 {@code selectedStart} 只在构造时确定，无需随点击刷新。
 */
public class PeriodOptionAdapter
        extends ListAdapter<PeriodOption, PeriodOptionAdapter.OptionViewHolder> {

    /** 周期选项点击回调。 */
    public interface OnOptionClickListener {
        void onOptionClick(@NonNull PeriodOption option);
    }

    private static final DiffUtil.ItemCallback<PeriodOption> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<PeriodOption>() {
                @Override
                public boolean areItemsTheSame(@NonNull PeriodOption oldItem,
                                               @NonNull PeriodOption newItem) {
                    return oldItem.start == newItem.start && oldItem.type == newItem.type;
                }

                @Override
                public boolean areContentsTheSame(@NonNull PeriodOption oldItem,
                                                  @NonNull PeriodOption newItem) {
                    return oldItem.transactionCount == newItem.transactionCount
                            && oldItem.end == newItem.end
                            && TextUtils.equals(oldItem.title, newItem.title)
                            && TextUtils.equals(oldItem.subtitle, newItem.subtitle);
                }
            };

    /** 当前所在周期首日 millis，用于高亮定位。 */
    private final long selectedStart;

    @Nullable
    private final OnOptionClickListener listener;

    public PeriodOptionAdapter(long selectedStart, @Nullable OnOptionClickListener listener) {
        super(DIFF_CALLBACK);
        this.selectedStart = selectedStart;
        this.listener = listener;
    }

    @NonNull
    @Override
    public OptionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPeriodOptionBinding binding = ItemPeriodOptionBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new OptionViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull OptionViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    /** 周期选项卡片：标题 + 日期范围 + 笔数，选中态换边框与底色。 */
    final class OptionViewHolder extends RecyclerView.ViewHolder {

        private final ItemPeriodOptionBinding binding;

        OptionViewHolder(@NonNull ItemPeriodOptionBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull PeriodOption option) {
            binding.optionTitle.setText(option.title);
            binding.optionSubtitle.setText(option.subtitle);
            binding.optionCount.setText(itemView.getResources()
                    .getString(R.string.period_option_count_format, option.transactionCount));

            boolean selected = option.start == selectedStart;
            binding.optionCard.setStrokeColor(ContextCompat.getColor(itemView.getContext(),
                    selected ? R.color.primary : R.color.divider));
            binding.optionCard.setCardBackgroundColor(ContextCompat.getColor(itemView.getContext(),
                    selected ? R.color.primary_light : R.color.surface));

            binding.optionCard.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onOptionClick(option);
                }
            });
        }
    }
}
