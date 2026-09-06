package com.skyanchor.bookkeeping.ui.sync;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.SyncEventEntity;
import com.skyanchor.bookkeeping.databinding.ItemSyncEventBinding;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 同步事件历史适配器（V3.1 基线第 25 章）：时间 + 结果 + 计数 + 耗时（+错误摘要）。 */
public class SyncEventAdapter extends ListAdapter<SyncEventEntity, SyncEventAdapter.Holder> {

    private static final DiffUtil.ItemCallback<SyncEventEntity> DIFF =
            new DiffUtil.ItemCallback<SyncEventEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull SyncEventEntity a,
                                               @NonNull SyncEventEntity b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull SyncEventEntity a,
                                                  @NonNull SyncEventEntity b) {
                    return a.id == b.id
                            && a.pushCount == b.pushCount
                            && a.pullCount == b.pullCount
                            && a.conflictCount == b.conflictCount
                            && a.durationMs == b.durationMs
                            && stringEquals(a.result, b.result)
                            && stringEquals(a.errorMessage, b.errorMessage);
                }

                private boolean stringEquals(String a, String b) {
                    return a == null ? b == null : a.equals(b);
                }
            };

    SyncEventAdapter() {
        super(DIFF);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemSyncEventBinding binding = ItemSyncEventBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        SyncEventEntity event = getItem(position);
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        holder.binding.eventTime.setText(timeFormat.format(new Date(event.startedAt)));
        int resultRes = "SUCCESS".equals(event.result)
                ? R.string.sync_status_success
                : ("WAITING_NETWORK".equals(event.result)
                ? R.string.sync_status_waiting_network : R.string.sync_status_error);
        holder.binding.eventSummary.setText(holder.binding.getRoot().getContext()
                .getString(R.string.sync_diag_event_format,
                        holder.binding.getRoot().getContext().getString(resultRes),
                        event.pushCount, event.pullCount, event.durationMs));
        if (event.errorMessage != null && !event.errorMessage.isEmpty()) {
            holder.binding.eventError.setVisibility(View.VISIBLE);
            holder.binding.eventError.setText(event.errorMessage);
        } else {
            holder.binding.eventError.setVisibility(View.GONE);
        }
    }

    static class Holder extends RecyclerView.ViewHolder {

        final ItemSyncEventBinding binding;

        Holder(@NonNull ItemSyncEventBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
