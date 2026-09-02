package com.skyanchor.bookkeeping.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.databinding.ItemCategoryRowBinding;

import java.util.List;

/**
 * 分类管理列表：emoji + 名称 + 上移 / 下移 / 编辑 / 删除（V1 基线第 6 章）。
 *
 * <p>数据已由 {@code CategoryDao.observeByType} 按 sortOrder 升序排好，这里只负责渲染，
 * 并把首尾两行的排序按钮置为禁用态。
 */
public class CategoryAdapter extends ListAdapter<CategoryEntity, CategoryAdapter.ViewHolder> {

    /** 行内四个动作的回调，全部由 Activity 落到仓库层执行。 */
    public interface Listener {
        void onMoveUp(@NonNull CategoryEntity category);

        void onMoveDown(@NonNull CategoryEntity category);

        void onEdit(@NonNull CategoryEntity category);

        void onDelete(@NonNull CategoryEntity category);
    }

    /** 上移方向，与 {@code BookkeepingRepository.moveCategory} 的约定一致。 */
    public static final int DIRECTION_UP = -1;

    /** 下移方向。 */
    public static final int DIRECTION_DOWN = 1;

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

    public CategoryAdapter(@Nullable Listener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
    }

    /**
     * 首尾判定依赖列表长度，而长度变化时 DiffUtil 只会重绑内容改变的行，
     * 原来的末行不会收到通知，排序按钮的可用状态就会停在旧值。
     * 分类总量很小，这里在长度变化时整体重绑一次。
     */
    @Override
    public void onCurrentListChanged(@NonNull List<CategoryEntity> previousList,
                                     @NonNull List<CategoryEntity> currentList) {
        super.onCurrentListChanged(previousList, currentList);
        if (previousList.size() != currentList.size()) {
            notifyItemRangeChanged(0, currentList.size());
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemCategoryRowBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position), position, getItemCount());
    }

    final class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemCategoryRowBinding binding;

        ViewHolder(@NonNull ItemCategoryRowBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull CategoryEntity category, int position, int itemCount) {
            binding.rowIcon.setText(category.icon);
            binding.rowName.setText(category.name);
            // 系统默认分类可以改名换图标，但删除守卫同样生效，这里标出来让用户有预期
            binding.rowBadge.setVisibility(category.isDefault ? View.VISIBLE : View.GONE);

            binding.rowMoveUp.setEnabled(position > 0);
            binding.rowMoveDown.setEnabled(position < itemCount - 1);

            binding.rowMoveUp.setOnClickListener(v -> dispatch(
                    target -> target.onMoveUp(category)));
            binding.rowMoveDown.setOnClickListener(v -> dispatch(
                    target -> target.onMoveDown(category)));
            binding.rowEdit.setOnClickListener(v -> dispatch(
                    target -> target.onEdit(category)));
            binding.rowDelete.setOnClickListener(v -> dispatch(
                    target -> target.onDelete(category)));
        }
    }

    /** 动作回调接口有四个方法，用一个小接口收敛空判断，避免每处都写 if。 */
    private interface Action {
        void run(@NonNull Listener listener);
    }

    private void dispatch(@NonNull Action action) {
        if (listener != null) {
            action.run(listener);
        }
    }
}
