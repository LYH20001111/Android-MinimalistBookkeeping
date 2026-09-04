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
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.model.SearchFilter;
import com.skyanchor.bookkeeping.databinding.ItemPickerAccountBinding;
import com.skyanchor.bookkeeping.databinding.ItemPickerSectionHeaderBinding;
import com.skyanchor.bookkeeping.util.AccountTypes;

/**
 * 搜索账户选择器的图标列表（V2.1 Phase 1）。
 *
 * <p>行模型是「区块标题 / 账户」的混合列表：顶部可带「最近使用」快捷区（无记录则省略），
 * 之后是「全部账户」伪账户（id={@link SearchFilter#NO_ACCOUNT}）与全量账户
 * （含已归档，历史账单可能落在归档账户上）。两种行类型各自持有 ViewHolder，
 * 避免把标题行误绑到账户行上。账户项按基线 5.4 只展示「图标 + 名称 + 类型」，不展示余额。
 */
public class PickerAccountAdapter
        extends ListAdapter<PickerAccountAdapter.Row, PickerAccountAdapter.ViewHolder> {

    /** 「全部账户」伪项使用的中性图标。 */
    public static final String PSEUDO_ALL_ICON = "🗂";

    public interface Listener {
        void onAccountPicked(@NonNull AccountEntity account);
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

    public PickerAccountAdapter(long selectedId, @Nullable Listener listener) {
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
        return new AccountViewHolder(
                ItemPickerAccountBinding.inflate(inflater, parent, false));
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

    /** 账户列表行。 */
    final class AccountViewHolder extends ViewHolder {

        private final ItemPickerAccountBinding binding;

        AccountViewHolder(@NonNull ItemPickerAccountBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        @Override
        void bind(@NonNull Row row) {
            AccountEntity account = requireAccount(row);
            boolean selected = account.id == selectedId;
            binding.getRoot().setSelected(selected);
            binding.pickerName.setText(account.name);
            binding.pickerName.setTextColor(ContextCompat.getColor(itemView.getContext(),
                    selected ? R.color.primary : R.color.text_primary));
            binding.pickerCheck.setVisibility(selected ? View.VISIBLE : View.GONE);
            if (account.id == SearchFilter.NO_ACCOUNT) {
                // 「全部账户」伪项：中性图标、无类型标签
                binding.pickerIcon.setText(PSEUDO_ALL_ICON);
                binding.pickerType.setVisibility(View.GONE);
            } else {
                binding.pickerIcon.setText(AccountTypes.emoji(account.type));
                binding.pickerType.setVisibility(View.VISIBLE);
                String typeLabel = itemView.getContext()
                        .getString(AccountTypes.labelRes(account.type));
                if (account.isArchived) {
                    typeLabel = typeLabel + " · "
                            + itemView.getContext().getString(R.string.account_archived_badge);
                }
                binding.pickerType.setText(typeLabel);
            }
            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) {
                    listener.onAccountPicked(account);
                }
            });
        }
    }

    @NonNull
    private static AccountEntity requireAccount(@NonNull Row row) {
        if (row.type != Row.TYPE_ACCOUNT || row.account == null) {
            throw new IllegalStateException("Account row expected, got type=" + row.type);
        }
        return row.account;
    }

    @NonNull
    private static String requireTitle(@NonNull Row row) {
        if (row.type != Row.TYPE_HEADER || row.title == null) {
            throw new IllegalStateException("Header row expected, got type=" + row.type);
        }
        return row.title;
    }

    /** 列表行：区块标题或账户项。 */
    public static final class Row {

        public static final int TYPE_HEADER = 0;
        public static final int TYPE_ACCOUNT = 1;

        public final int type;

        @Nullable
        public final String title;

        @Nullable
        public final AccountEntity account;

        private Row(int type, @Nullable String title, @Nullable AccountEntity account) {
            this.type = type;
            this.title = title;
            this.account = account;
        }

        @NonNull
        public static Row header(@NonNull String title) {
            return new Row(TYPE_HEADER, title, null);
        }

        @NonNull
        public static Row of(@NonNull AccountEntity account) {
            return new Row(TYPE_ACCOUNT, null, account);
        }

        boolean sameIdentity(@NonNull Row other) {
            if (type != other.type) {
                return false;
            }
            if (type == TYPE_HEADER) {
                return title != null && title.equals(other.title);
            }
            return account != null && other.account != null
                    && account.id == other.account.id;
        }

        boolean sameContent(@NonNull Row other) {
            if (type != other.type) {
                return false;
            }
            if (type == TYPE_HEADER) {
                return title != null && title.equals(other.title);
            }
            return account != null && account.equals(other.account);
        }
    }
}
