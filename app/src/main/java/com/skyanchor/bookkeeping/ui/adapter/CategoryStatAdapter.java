package com.skyanchor.bookkeeping.ui.adapter;

import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.skyanchor.bookkeeping.data.model.CategoryStat;
import com.skyanchor.bookkeeping.databinding.ItemCategoryStatBinding;
import com.skyanchor.bookkeeping.util.AmountUtil;

/**
 * 分类占比排名列表（V1 基线第 7.1 节第三层）：色点 + 分类名 + 金额 + 占比 + 细进度条。
 *
 * <p>数据已由 {@code StatisticsCalculator.categoryBreakdown} 按金额降序排好，这里只负责渲染。
 * 色点与进度条都使用 {@link CategoryStat#color}，与环形图分段严格同色。
 */
public class CategoryStatAdapter extends ListAdapter<CategoryStat, CategoryStatAdapter.ViewHolder> {

    private static final DiffUtil.ItemCallback<CategoryStat> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<CategoryStat>() {
                @Override
                public boolean areItemsTheSame(@NonNull CategoryStat oldItem,
                                               @NonNull CategoryStat newItem) {
                    return oldItem.categoryId == newItem.categoryId;
                }

                @Override
                public boolean areContentsTheSame(@NonNull CategoryStat oldItem,
                                                  @NonNull CategoryStat newItem) {
                    return oldItem.amount == newItem.amount
                            && oldItem.percentX10 == newItem.percentX10
                            && oldItem.color == newItem.color
                            && TextUtils.equals(oldItem.name, newItem.name)
                            && TextUtils.equals(oldItem.icon, newItem.icon);
                }
            };

    public CategoryStatAdapter() {
        super(DIFF_CALLBACK);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemCategoryStatBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemCategoryStatBinding binding;

        ViewHolder(@NonNull ItemCategoryStatBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull CategoryStat stat) {
            Drawable dot = binding.statDot.getBackground();
            if (dot != null) {
                // mutate 后该 Drawable 不再与其他行共享常量状态，染色不会互相污染
                DrawableCompat.setTint(dot.mutate(), stat.color);
            }
            binding.statIcon.setText(stat.icon);
            binding.statName.setText(stat.name);
            binding.statAmount.setText(AmountUtil.format(stat.amount));
            binding.statPercent.setText(stat.percentText());
            binding.statProgress.setIndicatorColor(stat.color);
            binding.statProgress.setProgressCompat(stat.percentX10, false);
        }
    }
}
