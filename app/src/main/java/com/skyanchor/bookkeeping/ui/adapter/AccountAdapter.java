package com.skyanchor.bookkeeping.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.model.AccountBalance;
import com.skyanchor.bookkeeping.databinding.ItemAccountRowBinding;
import com.skyanchor.bookkeeping.util.AccountTypes;
import com.skyanchor.bookkeeping.util.AmountUtil;

/**
 * 账户管理列表（V2 新增）：emoji + 名称 + 类型 + 余额 + 编辑 / 删除。
 *
 * <p>数据源是 {@code AccountDao.observeAccountBalances()} 的联表重算投影，
 * 因此余额始终等于「初始 + 收入 - 支出 + 转入 - 转出」的真值，账单变化后自动刷新。
 * 余额可正可负，负数（如信用卡欠款）用 danger 语义色提示。
 *
 * <p>归档 / 取消归档放在编辑弹窗内，列表行只保留编辑与删除两个高频动作，保持极简。
 */
public class AccountAdapter extends ListAdapter<AccountBalance, AccountAdapter.ViewHolder> {

    /** 行内动作回调，由 Activity 落到 ViewModel / 仓库层执行。 */
    public interface Listener {
        void onEdit(@NonNull AccountBalance account);

        void onDelete(@NonNull AccountBalance account);
    }

    private static final DiffUtil.ItemCallback<AccountBalance> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<AccountBalance>() {
                @Override
                public boolean areItemsTheSame(@NonNull AccountBalance oldItem,
                                               @NonNull AccountBalance newItem) {
                    return oldItem.id == newItem.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull AccountBalance oldItem,
                                                  @NonNull AccountBalance newItem) {
                    return oldItem.equals(newItem);
                }
            };

    @Nullable
    private final Listener listener;

    public AccountAdapter(@Nullable Listener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemAccountRowBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    final class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemAccountRowBinding binding;

        ViewHolder(@NonNull ItemAccountRowBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull AccountBalance account) {
            binding.rowIcon.setText(AccountTypes.emoji(account.type));
            binding.rowName.setText(account.name);
            binding.rowType.setText(AccountTypes.labelRes(account.type));
            binding.rowArchived.setVisibility(account.isArchived ? View.VISIBLE : View.GONE);

            binding.rowBalance.setText(AmountUtil.format(account.balance));
            // 余额可正可负：负数（信用卡欠款等）用 danger 色，其余用主文本色。
            binding.rowBalance.setTextColor(ContextCompat.getColor(itemView.getContext(),
                    account.balance < 0L ? R.color.danger : R.color.text_primary));

            binding.rowEdit.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onEdit(account);
                }
            });
            binding.rowDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDelete(account);
                }
            });
        }
    }
}
