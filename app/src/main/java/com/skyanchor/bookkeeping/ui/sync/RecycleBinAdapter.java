package com.skyanchor.bookkeeping.ui.sync;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.skyanchor.bookkeeping.databinding.ItemRecycleBinBinding;

/**
 * 回收站列表适配器（V3.1 基线第 18 章）：统一行模型覆盖四类实体，
 * 恢复按钮回调宿主 Activity 执行（仓库层负责入队同步）。
 */
public class RecycleBinAdapter extends ListAdapter<RecycleBinAdapter.Row,
        RecycleBinAdapter.Holder> {

    /** 回收站统一行模型：四类实体各取展示所需字段。 */
    public static final class Row {
        public final long localId;
        @NonNull
        public final String title;
        @NonNull
        public final String meta;

        public Row(long localId, @NonNull String title, @NonNull String meta) {
            this.localId = localId;
            this.title = title;
            this.meta = meta;
        }
    }

    public interface Listener {
        void onRestore(@NonNull Row row);
    }

    private final Listener listener;

    public RecycleBinAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<Row> DIFF =
            new DiffUtil.ItemCallback<Row>() {
                @Override
                public boolean areItemsTheSame(@NonNull Row a, @NonNull Row b) {
                    return a.localId == b.localId;
                }

                @Override
                public boolean areContentsTheSame(@NonNull Row a, @NonNull Row b) {
                    return a.localId == b.localId
                            && a.title.equals(b.title)
                            && a.meta.equals(b.meta);
                }
            };

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemRecycleBinBinding binding = ItemRecycleBinBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Row row = getItem(position);
        holder.binding.itemTitle.setText(row.title);
        holder.binding.itemMeta.setText(row.meta);
        holder.binding.restoreButton.setOnClickListener(v -> listener.onRestore(row));
    }

    static class Holder extends RecyclerView.ViewHolder {

        final ItemRecycleBinBinding binding;

        Holder(@NonNull ItemRecycleBinBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
