package com.skyanchor.bookkeeping.ui.adapter;

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
import com.skyanchor.bookkeeping.databinding.ItemCategoryGridBinding;

import java.util.List;

/**
 * 记一笔页面的分类单选网格。
 *
 * <p>选中态不属于实体字段，因此不放进 DiffUtil，而是在切换选中项时
 * 精确刷新「旧选中」与「新选中」两行，避免整表重绘。
 */
public class CategoryGridAdapter extends ListAdapter<CategoryEntity, CategoryGridAdapter.ViewHolder> {

    /** 分类被点击时的回调。 */
    public interface Listener {
        void onCategorySelected(@NonNull CategoryEntity category);
    }

    private static final DiffUtil.ItemCallback<CategoryEntity> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<CategoryEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull CategoryEntity oldItem,
                                               @NonNull CategoryEntity newItem) {
                    return oldItem.id == newItem.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull CategoryEntity oldItem,
                                                  @NonNull CategoryEntity newItem) {
                    return oldItem.equals(newItem);
                }
            };

    @Nullable
    private final Listener listener;

    private long selectedId;

    public CategoryGridAdapter(@Nullable Listener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
    }

    /** 设置选中分类，只刷新受影响的两行。 */
    public void setSelectedId(long categoryId) {
        if (selectedId == categoryId) {
            return;
        }
        long previous = selectedId;
        selectedId = categoryId;
        List<CategoryEntity> list = getCurrentList();
        for (int i = 0; i < list.size(); i++) {
            long id = list.get(i).id;
            if (id == previous || id == categoryId) {
                notifyItemChanged(i);
            }
        }
    }

    public long getSelectedId() {
        return selectedId;
    }

    /** 当前选中的分类，未选中时返回 null。 */
    @Nullable
    public CategoryEntity getSelectedCategory() {
        for (CategoryEntity category : getCurrentList()) {
            if (category.id == selectedId) {
                return category;
            }
        }
        return null;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemCategoryGridBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    final class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemCategoryGridBinding binding;

        ViewHolder(@NonNull ItemCategoryGridBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull CategoryEntity category) {
            boolean selected = category.id == selectedId;
            binding.getRoot().setSelected(selected);
            binding.gridIcon.setText(category.icon);
            binding.gridName.setText(category.name);
            binding.gridName.setTextColor(ContextCompat.getColor(itemView.getContext(),
                    selected ? R.color.primary : R.color.text_secondary));
            binding.getRoot().setOnClickListener(v -> {
                setSelectedId(category.id);
                if (listener != null) {
                    listener.onCategorySelected(category);
                }
            });
        }
    }
}
