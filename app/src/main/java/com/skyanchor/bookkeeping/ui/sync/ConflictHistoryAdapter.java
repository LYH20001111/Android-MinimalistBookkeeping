package com.skyanchor.bookkeeping.ui.sync;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.databinding.ItemConflictHistoryBinding;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 冲突历史适配器（V3.1 基线第 26 章）：自动收敛留痕，只读展示。 */
public class ConflictHistoryAdapter
        extends ListAdapter<ConflictHistoryViewModel.Row, ConflictHistoryAdapter.Holder> {

    private static final DiffUtil.ItemCallback<ConflictHistoryViewModel.Row> DIFF =
            new DiffUtil.ItemCallback<ConflictHistoryViewModel.Row>() {
                @Override
                public boolean areItemsTheSame(@NonNull ConflictHistoryViewModel.Row a,
                                               @NonNull ConflictHistoryViewModel.Row b) {
                    return a.item.id == b.item.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull ConflictHistoryViewModel.Row a,
                                                  @NonNull ConflictHistoryViewModel.Row b) {
                    return a.item.id == b.item.id
                            && a.entityLabel.equals(b.entityLabel)
                            && a.item.winner.equals(b.item.winner);
                }
            };

    ConflictHistoryAdapter() {
        super(DIFF);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemConflictHistoryBinding binding = ItemConflictHistoryBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ConflictHistoryViewModel.Row row = getItem(position);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        holder.binding.conflictTitle.setText(holder.binding.getRoot().getContext()
                .getString(R.string.conflict_item_title_format, row.entityLabel));
        holder.binding.conflictTime.setText(format.format(new Date(row.item.createdAt)));
        holder.binding.conflictResult.setText("SERVER".equals(row.item.winner)
                ? R.string.conflict_item_winner_server
                : R.string.conflict_item_winner_client);
        String device = row.item.clientDeviceId == null || row.item.clientDeviceId.isEmpty()
                ? "***" : row.item.clientDeviceId;
        holder.binding.conflictSource.setText(
                holder.binding.getRoot().getContext()
                        .getString(R.string.conflict_item_source_format, device));
    }

    static class Holder extends RecyclerView.ViewHolder {

        final ItemConflictHistoryBinding binding;

        Holder(@NonNull ItemConflictHistoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
