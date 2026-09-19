package com.skyanchor.bookkeeping.ui.adapter;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.RefundRecordEntity;
import com.skyanchor.bookkeeping.databinding.ItemRefundRecordBinding;
import com.skyanchor.bookkeeping.util.AmountUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

/**
 * 退款流水适配器（V4.0）：最新在前，状态标签配色与预算 / 图表共用同一组语义色
 * （待到账 warning、已到账 success、已取消 danger）。
 *
 * <p>已取消的行只读——它是历史证据，不再提供操作；待到账行可标记到账，
 * 待到账与已到账行都可撤销。
 */
public class RefundRecordAdapter
        extends ListAdapter<RefundRecordEntity, RefundRecordAdapter.Holder> {

    /** 行内操作回调。 */
    public interface Listener {
        void onMarkReceived(long refundId);

        void onEdit(RefundRecordEntity refund);

        void onCancel(RefundRecordEntity refund);
    }

    private static final DiffUtil.ItemCallback<RefundRecordEntity> DIFF =
            new DiffUtil.ItemCallback<RefundRecordEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull RefundRecordEntity a,
                                               @NonNull RefundRecordEntity b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull RefundRecordEntity a,
                                                  @NonNull RefundRecordEntity b) {
                    return a.state.equals(b.state)
                            && a.amount == b.amount
                            && a.requestedAt == b.requestedAt
                            && Objects.equals(a.receivedAt, b.receivedAt)
                            && a.updatedAt == b.updatedAt
                            && TextUtils.equals(a.reason, b.reason);
                }
            };

    @NonNull
    private final Listener listener;

    public RefundRecordAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemRefundRecordBinding binding = ItemRefundRecordBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        RefundRecordEntity refund = getItem(position);
        Context context = holder.binding.getRoot().getContext();
        holder.binding.refundAmount.setText("-" + AmountUtil.format(refund.amount));
        holder.binding.refundState.setText(context.getString(stateLabel(refund)));
        holder.binding.refundState.setTextColor(ContextCompat.getColor(context, stateColor(refund)));
        bindTimes(holder, context, refund);
        bindReason(holder, context, refund);
        bindActions(holder, refund);
    }

    private void bindTimes(@NonNull Holder holder, @NonNull Context context,
                           @NonNull RefundRecordEntity refund) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        String requested = format.format(new Date(refund.requestedAt));
        if (refund.isPending()) {
            holder.binding.refundTime.setText(
                    context.getString(R.string.refund_record_requested_format, requested));
            return;
        }
        // 已取消行没有独立取消时间字段，以 updatedAt 作为撤销时刻；
        // 已到账行优先用 receivedAt，缺失时同样回落到 updatedAt。
        Long shown = refund.isReceived() && refund.receivedAt != null
                ? refund.receivedAt : refund.updatedAt;
        holder.binding.refundTime.setText(context.getString(
                refund.isReceived()
                        ? R.string.refund_record_received_format
                        : R.string.refund_record_cancelled_format,
                requested, format.format(new Date(shown))));
    }

    private void bindReason(@NonNull Holder holder, @NonNull Context context,
                            @NonNull RefundRecordEntity refund) {
        String reason = refund.reason;
        if (TextUtils.isEmpty(reason)) {
            holder.binding.refundReason.setVisibility(View.GONE);
            return;
        }
        holder.binding.refundReason.setVisibility(View.VISIBLE);
        holder.binding.refundReason.setText(context.getString(
                R.string.refund_record_reason_format, reason));
    }

    private void bindActions(@NonNull Holder holder, @NonNull RefundRecordEntity refund) {
        if (refund.isCancelled()) {
            holder.binding.refundActions.setVisibility(View.GONE);
            return;
        }
        holder.binding.refundActions.setVisibility(View.VISIBLE);
        holder.binding.refundActionEdit.setVisibility(View.VISIBLE);
        holder.binding.refundActionEdit.setOnClickListener(v -> listener.onEdit(refund));
        holder.binding.refundActionReceived.setVisibility(
                refund.isPending() ? View.VISIBLE : View.GONE);
        holder.binding.refundActionReceived.setOnClickListener(
                v -> listener.onMarkReceived(refund.id));
        holder.binding.refundActionCancel.setOnClickListener(
                v -> listener.onCancel(refund));
    }

    @StringRes
    private static int stateLabel(@NonNull RefundRecordEntity refund) {
        if (refund.isCancelled()) {
            return R.string.refund_record_state_cancelled;
        }
        return refund.isReceived()
                ? R.string.refund_record_state_received
                : R.string.refund_record_state_pending;
    }

    private static int stateColor(@NonNull RefundRecordEntity refund) {
        if (refund.isCancelled()) {
            return R.color.danger;
        }
        return refund.isReceived() ? R.color.success : R.color.warning;
    }

    static class Holder extends RecyclerView.ViewHolder {

        final ItemRefundRecordBinding binding;

        Holder(@NonNull ItemRefundRecordBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
