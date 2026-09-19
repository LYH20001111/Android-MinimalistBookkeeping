package com.skyanchor.bookkeeping.ui.record;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.RefundRecordEntity;
import com.skyanchor.bookkeeping.databinding.SheetRefundBinding;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.InsetsUtil;

/**
 * 退款表单（V4.0）：为一笔支出账单登记退款，或回改一笔已有退款。
 *
 * <p>两种模式共用同一套字段与校验：{@code editing == null} 时从编辑账单页右上角菜单进入、
 * 新建一笔退款；传入 {@code editing} 时从退款记录页的「编辑」进入，预填该笔的金额 / 原因 /
 * 到账状态，此时 {@code quota} 是「原额 − 其他退款累计」，即这一笔单独可改到的上限。
 *
 * <p>超出上限时就地报错、不关闭弹窗；校验只挡明显误操作，最终以
 * {@code BookkeepingRepository.requestRefund} / {@code updateRefund} 在同一事务内的复核为准，
 * 避免多设备并发退款造成超额。
 */
public class RefundSheetDialog extends BottomSheetDialog {

    /** 用户确认退款后的回调，参数为退款金额（分）、原因（可空）与是否已到账。 */
    public interface Listener {
        void onConfirm(long amountCents, @Nullable String reason, boolean toReceived);
    }

    private SheetRefundBinding binding;
    private final long originalAmount;
    private final long quota;

    /** 被编辑的退款流水；新建模式下为 null。 */
    @Nullable
    private final RefundRecordEntity editing;

    @NonNull
    private final Listener listener;

    public RefundSheetDialog(@NonNull Context context, long originalAmount, long quota,
                             @NonNull Listener listener) {
        this(context, originalAmount, quota, null, listener);
    }

    /** 编辑模式：{@code editing} 非空时预填其金额、原因与到账状态。 */
    public RefundSheetDialog(@NonNull Context context, long originalAmount, long quota,
                             @Nullable RefundRecordEntity editing, @NonNull Listener listener) {
        super(context);
        this.originalAmount = originalAmount;
        this.quota = quota;
        this.editing = editing;
        this.listener = listener;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = SheetRefundBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsUtil.applyBottomPadding(binding.refundSheetRoot);

        binding.refundQuota.setText(getContext().getString(
                R.string.refund_sheet_quota_format,
                AmountUtil.format(originalAmount), AmountUtil.format(quota)));
        RefundRecordEntity target = editing;
        if (target != null) {
            binding.sheetTitle.setText(R.string.refund_edit_title);
            binding.refundSubmit.setText(R.string.refund_edit_submit);
            binding.refundAmountInput.setText(AmountUtil.toInputText(target.amount));
            if (binding.refundAmountInput.getText() != null) {
                binding.refundAmountInput.setSelection(
                        binding.refundAmountInput.getText().length());
            }
            binding.refundReasonInput.setText(
                    TextUtils.isEmpty(target.reason) ? "" : target.reason);
        }
        binding.refundStateGroup.check(target != null && target.isReceived()
                ? R.id.stateReceived : R.id.statePending);
        binding.refundStateGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                binding.refundStateTip.setVisibility(checkedId == R.id.stateReceived
                        ? View.INVISIBLE : View.VISIBLE);
            }
        });
        // 选中项是代码设的，监听器只在变化时回调，故初始状态手动同步一次。
        binding.refundStateTip.setVisibility(
                binding.refundStateGroup.getCheckedButtonId() == R.id.stateReceived
                        ? View.INVISIBLE : View.VISIBLE);

        binding.refundAmountInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                binding.refundAmountLayout.setError(null);
            }
        });

        binding.refundAllButton.setOnClickListener(v -> {
            binding.refundAmountInput.setText(AmountUtil.toInputText(quota));
            binding.refundAmountInput.setSelection(
                    binding.refundAmountInput.getText() == null
                            ? 0 : binding.refundAmountInput.getText().length());
            binding.refundAmountLayout.setError(null);
        });

        binding.refundSubmit.setOnClickListener(v -> submit());
    }

    private void submit() {
        long cents = AmountUtil.parseToCents(
                binding.refundAmountInput.getText() == null
                        ? "" : binding.refundAmountInput.getText().toString());
        if (cents <= 0L) {
            binding.refundAmountLayout.setError(
                    getContext().getString(R.string.refund_error_amount));
            binding.refundAmountInput.requestFocus();
            return;
        }
        if (cents > quota) {
            binding.refundAmountLayout.setError(getContext().getString(
                    R.string.refund_error_quota, AmountUtil.format(quota)));
            binding.refundAmountInput.requestFocus();
            return;
        }
        String reason = binding.refundReasonInput.getText() == null
                ? "" : binding.refundReasonInput.getText().toString().trim();
        listener.onConfirm(cents, TextUtils.isEmpty(reason) ? null : reason,
                binding.refundStateGroup.getCheckedButtonId() == R.id.stateReceived);
        dismiss();
    }
}
