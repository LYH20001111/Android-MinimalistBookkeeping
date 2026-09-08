package com.skyanchor.bookkeeping.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.TransactionEditLogEntity;
import com.skyanchor.bookkeeping.databinding.ItemTransactionEditLogBinding;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 账单编辑记录适配器（V3.3）：只读展示创建与每次修改的字段级变更，最新在前。
 * 操作类型映射为中文标签，明细为仓库写入时刻生成的多行文本。
 */
public class TransactionEditLogAdapter
        extends ListAdapter<TransactionEditLogEntity, TransactionEditLogAdapter.Holder> {

    private static final DiffUtil.ItemCallback<TransactionEditLogEntity> DIFF =
            new DiffUtil.ItemCallback<TransactionEditLogEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull TransactionEditLogEntity a,
                                               @NonNull TransactionEditLogEntity b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull TransactionEditLogEntity a,
                                                  @NonNull TransactionEditLogEntity b) {
                    return a.id == b.id
                            && a.changedAt == b.changedAt
                            && a.operation.equals(b.operation)
                            && a.detail.equals(b.detail);
                }
            };

    public TransactionEditLogAdapter() {
        super(DIFF);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemTransactionEditLogBinding binding = ItemTransactionEditLogBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        TransactionEditLogEntity log = getItem(position);
        int operationLabel = TransactionEditLogEntity.OP_CREATE.equals(log.operation)
                ? R.string.edit_history_op_create : R.string.edit_history_op_update;
        holder.binding.logOperation.setText(operationLabel);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        holder.binding.logTime.setText(format.format(new Date(log.changedAt)));
        holder.binding.logDetail.setText(log.detail);
    }

    static class Holder extends RecyclerView.ViewHolder {

        final ItemTransactionEditLogBinding binding;

        Holder(@NonNull ItemTransactionEditLogBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
