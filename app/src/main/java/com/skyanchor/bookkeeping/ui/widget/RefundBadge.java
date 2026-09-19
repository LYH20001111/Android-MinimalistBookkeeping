package com.skyanchor.bookkeeping.ui.widget;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.domain.refund.RefundPolicy;
import com.skyanchor.bookkeeping.util.AmountUtil;

/**
 * 列表退款角标（V4.0）：账单行金额仍显示原额，角标说明这笔账已发生的退款，
 * 避免「列表原额」与「统计净额」看起来对不上。
 *
 * <p>状态一律由「原额 / 已退 / 待退」三个数推导（见 {@link RefundPolicy#displayState}），
 * 配色沿用记录页与流水页的同一组语义色：待到账 warning、已到账 success。
 */
public final class RefundBadge {

    private RefundBadge() {
    }

    /** 无退款时隐藏角标；调用方无需自己判空，ViewHolder 复用时也会复位。 */
    public static void bind(@NonNull TextView badge, @Nullable TransactionItem item) {
        if (item == null) {
            badge.setVisibility(View.GONE);
            return;
        }
        bind(badge, item.amount, item.refundedAmount, item.pendingRefundAmount);
    }

    public static void bind(@NonNull TextView badge, long amount,
                            long refundedAmount, long pendingAmount) {
        RefundPolicy.DisplayState state =
                RefundPolicy.displayState(amount, refundedAmount, pendingAmount);
        if (state == RefundPolicy.DisplayState.NONE) {
            badge.setVisibility(View.GONE);
            return;
        }
        String text;
        int colorRes;
        if (state == RefundPolicy.DisplayState.PENDING) {
            text = badge.getContext().getString(R.string.refund_badge_pending);
            colorRes = R.color.warning;
        } else if (state == RefundPolicy.DisplayState.FULL) {
            text = badge.getContext().getString(R.string.refund_badge_full);
            colorRes = R.color.success;
        } else {
            text = badge.getContext().getString(
                    R.string.refund_badge_partial, AmountUtil.format(refundedAmount));
            colorRes = R.color.success;
        }
        badge.setVisibility(View.VISIBLE);
        badge.setText(text);
        badge.setTextColor(ContextCompat.getColor(badge.getContext(), colorRes));
    }
}
