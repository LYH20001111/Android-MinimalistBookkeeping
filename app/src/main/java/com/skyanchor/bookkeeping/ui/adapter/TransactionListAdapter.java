package com.skyanchor.bookkeeping.ui.adapter;

import android.text.TextUtils;
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
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.data.model.RecordListItem;
import com.skyanchor.bookkeeping.databinding.ItemRecordHeaderBinding;
import com.skyanchor.bookkeeping.databinding.ItemTransactionRowBinding;
import com.skyanchor.bookkeeping.util.AmountUtil;

/**
 * 记录页账单列表适配器：一个列表里混合「日期分组标题」与「账单行」两种类型。
 *
 * <p>使用 {@link ListAdapter} + {@link DiffUtil}，新增/编辑/删除后只重绘变化的行，
 * 避免整表刷新造成的闪烁。
 */
public class TransactionListAdapter extends ListAdapter<RecordListItem, RecyclerView.ViewHolder> {

    /** 列表交互回调。 */
    public interface Listener {

        /** 单击账单行，进入编辑。 */
        void onTransactionClick(@NonNull TransactionItem item);

        /** 长按账单行，弹出删除二次确认。 */
        void onTransactionLongClick(@NonNull TransactionItem item);
    }

    private static final DiffUtil.ItemCallback<RecordListItem> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<RecordListItem>() {
                @Override
                public boolean areItemsTheSame(@NonNull RecordListItem oldItem,
                                               @NonNull RecordListItem newItem) {
                    if (oldItem.viewType() != newItem.viewType()) {
                        return false;
                    }
                    if (oldItem instanceof RecordListItem.Header) {
                        return ((RecordListItem.Header) oldItem).dayMillis
                                == ((RecordListItem.Header) newItem).dayMillis;
                    }
                    TransactionItem left = ((RecordListItem.Row) oldItem).item;
                    TransactionItem right = ((RecordListItem.Row) newItem).item;
                    return left != null && right != null && left.id == right.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull RecordListItem oldItem,
                                                  @NonNull RecordListItem newItem) {
                    return oldItem.equals(newItem);
                }
            };

    @Nullable
    private final Listener listener;

    public TransactionListAdapter(@Nullable Listener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).viewType();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == RecordListItem.VIEW_TYPE_HEADER) {
            return new HeaderViewHolder(ItemRecordHeaderBinding.inflate(inflater, parent, false));
        }
        return new RowViewHolder(ItemTransactionRowBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        RecordListItem item = getItem(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind((RecordListItem.Header) item);
        } else if (holder instanceof RowViewHolder) {
            ((RowViewHolder) holder).bind((RecordListItem.Row) item);
        }
    }

    /** 日期分组标题：左侧标签与笔数，右侧当日支出合计。 */
    private final class HeaderViewHolder extends RecyclerView.ViewHolder {

        private final ItemRecordHeaderBinding binding;

        HeaderViewHolder(@NonNull ItemRecordHeaderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull RecordListItem.Header header) {
            binding.headerLabel.setText(header.label);
            binding.headerCount.setText(itemView.getResources()
                    .getString(R.string.record_day_count_format, header.count));
            binding.headerAmount.setText(AmountUtil.format(header.expense));
        }
    }

    /** 账单行：emoji 圆形底 + 分类名 + 备注 + 时间 + 带符号金额。 */
    private final class RowViewHolder extends RecyclerView.ViewHolder {

        private final ItemTransactionRowBinding binding;

        RowViewHolder(@NonNull ItemTransactionRowBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull RecordListItem.Row row) {
            final TransactionItem item = row.item;
            if (item == null) {
                return;
            }
            binding.rowIcon.setText(item.displayIcon());
            binding.rowTitle.setText(item.displayName());

            if (TextUtils.isEmpty(item.note)) {
                binding.rowNote.setVisibility(View.GONE);
            } else {
                binding.rowNote.setVisibility(View.VISIBLE);
                binding.rowNote.setText(item.note);
            }

            binding.rowAmount.setText(AmountUtil.formatSigned(item.amount, item.isIncome()));
            binding.rowAmount.setTextColor(ContextCompat.getColor(itemView.getContext(),
                    item.isIncome() ? R.color.success : R.color.text_primary));
            binding.rowTime.setText(item.time);

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onTransactionClick(item);
                }
            });
            itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onTransactionLongClick(item);
                }
                return true;
            });
        }
    }
}
