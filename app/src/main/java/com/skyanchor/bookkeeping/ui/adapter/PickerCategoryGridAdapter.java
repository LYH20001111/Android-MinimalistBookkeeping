package com.skyanchor.bookkeeping.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.databinding.ItemPickerCategoryBinding;
import com.skyanchor.bookkeeping.databinding.ItemPickerSectionHeaderBinding;

import java.util.List;

/**
 * 搜索分类选择器的图标网格（V2.1 Phase 1）。
 *
 * <p>行模型是「区块标题 / 分类」的混合列表：顶部可带「最近使用」快捷区（无记录则省略），
 * 之后是「全部」伪分类（id=0，即筛选器的不限哨兵）与全量分类。
 * 两种行类型各自持有 ViewHolder（标题行是普通 TextView，网格里独占整行——
 * 由弹窗的 {@code SpanSizeLookup} 控制），避免把标题行误绑到分类行上。
 * 选中态用主色描边底 + ✓ 徽标共同表达，不单靠颜色（基线 5.3 交互要求 4）。
 */
public class PickerCategoryGridAdapter
        extends ListAdapter<PickerCategoryGridAdapter.Row, PickerCategoryGridAdapter.ViewHolder> {

    /** 选中分类时的回调。 */
    public interface Listener {
        void onCategoryPicked(@NonNull CategoryEntity category);
    }

    private static final DiffUtil.ItemCallback<Row> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<Row>() {
                @Override
                public boolean areItemsTheSame(@NonNull Row oldItem, @NonNull Row newItem) {
                    return oldItem.sameIdentity(newItem);
                }

                @Override
                public boolean areContentsTheSame(@NonNull Row oldItem, @NonNull Row newItem) {
                    return oldItem.sameContent(newItem);
                }
            };

    @Nullable
    private final Listener listener;

    private final long selectedId;

    public PickerCategoryGridAdapter(long selectedId, @Nullable Listener listener) {
        super(DIFF_CALLBACK);
        this.selectedId = selectedId;
        this.listener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).type;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == Row.TYPE_HEADER) {
            return new HeaderViewHolder(
                    ItemPickerSectionHeaderBinding.inflate(inflater, parent, false));
        }
        return new CategoryViewHolder(
                ItemPickerCategoryBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    public abstract static class ViewHolder extends RecyclerView.ViewHolder {

        ViewHolder(@NonNull View itemView) {
            super(itemView);
        }

        abstract void bind(@NonNull Row row);
    }

    /** 区块标题行（如「最近使用」）。 */
    static final class HeaderViewHolder extends ViewHolder {

        private final ItemPickerSectionHeaderBinding binding;

        HeaderViewHolder(@NonNull ItemPickerSectionHeaderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        @Override
        void bind(@NonNull Row row) {
            binding.pickerSectionTitle.setText(requireTitle(row));
        }
    }

    /** 分类网格行。 */
    final class CategoryViewHolder extends ViewHolder {

        private final ItemPickerCategoryBinding binding;

        CategoryViewHolder(@NonNull ItemPickerCategoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        @Override
        void bind(@NonNull Row row) {
            CategoryEntity category = requireCategory(row);
            boolean selected = category.id == selectedId;
            binding.getRoot().setSelected(selected);
            binding.pickerIcon.setText(category.icon);
            binding.pickerName.setText(category.name);
            binding.pickerName.setTextColor(ContextCompat.getColor(itemView.getContext(),
                    selected ? R.color.primary : R.color.text_secondary));
            binding.pickerCheck.setVisibility(selected ? View.VISIBLE : View.GONE);
            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) {
                    listener.onCategoryPicked(category);
                }
            });
        }
    }

    @NonNull
    private static CategoryEntity requireCategory(@NonNull Row row) {
        if (row.type != Row.TYPE_CATEGORY || row.category == null) {
            throw new IllegalStateException("Category row expected, got type=" + row.type);
        }
        return row.category;
    }

    @NonNull
    private static String requireTitle(@NonNull Row row) {
        if (row.type != Row.TYPE_HEADER || row.title == null) {
            throw new IllegalStateException("Header row expected, got type=" + row.type);
        }
        return row.title;
    }

    /** 网格行：区块标题或分类项。 */
    public static final class Row {

        public static final int TYPE_HEADER = 0;
        public static final int TYPE_CATEGORY = 1;

        public final int type;

        @Nullable
        public final String title;

        @Nullable
        public final CategoryEntity category;

        private Row(int type, @Nullable String title, @Nullable CategoryEntity category) {
            this.type = type;
            this.title = title;
            this.category = category;
        }

        @NonNull
        public static Row header(@NonNull String title) {
            return new Row(TYPE_HEADER, title, null);
        }

        @NonNull
        public static Row of(@NonNull CategoryEntity category) {
            return new Row(TYPE_CATEGORY, null, category);
        }

        boolean sameIdentity(@NonNull Row other) {
            if (type != other.type) {
                return false;
            }
            if (type == TYPE_HEADER) {
                return title != null && title.equals(other.title);
            }
            return category != null && other.category != null
                    && category.id == other.category.id;
        }

        boolean sameContent(@NonNull Row other) {
            if (type != other.type) {
                return false;
            }
            if (type == TYPE_HEADER) {
                return title != null && title.equals(other.title);
            }
            return category != null && category.equals(other.category);
        }
    }
}
