package com.skyanchor.bookkeeping.ui.record;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.RefundRecordEntity;
import com.skyanchor.bookkeeping.databinding.ActivityRefundRecordBinding;
import com.skyanchor.bookkeeping.domain.refund.RefundPolicy;
import com.skyanchor.bookkeeping.ui.adapter.RefundRecordAdapter;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.InsetsUtil;

import java.util.List;

/**
 * 退款记录页（V4.0）：从编辑账单页右上角菜单进入，展示一笔账单的全部退款流水。
 *
 * <p>页首汇总直接由观察到的流水累加，不读账单行上的缓存列，因此「编辑 / 标记到账 / 撤销」
 * 之后界面与统计口径同步刷新。每行未取消的流水都带「编辑」入口（金额、原因、到账状态
 * 均可回改，含把「退款中」直接改成已到账）；已取消的流水只读留档。
 */
public class RefundRecordActivity extends AppCompatActivity {

    public static final String EXTRA_TRANSACTION_ID = "extra_transaction_id";

    private ActivityRefundRecordBinding binding;
    private RefundRecordViewModel viewModel;

    /** 最近一次观察到的流水，账单原额晚到时用它补算汇总。 */
    @Nullable
    private List<RefundRecordEntity> refunds;
    @Nullable
    private Long originalAmount;

    public static void start(@NonNull Context context, long transactionId) {
        Intent intent = new Intent(context, RefundRecordActivity.class);
        intent.putExtra(EXTRA_TRANSACTION_ID, transactionId);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRefundRecordBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsUtil.applyTopAndHorizontalPadding(binding.refundRecordRoot);
        InsetsUtil.syncSystemBarAppearance(this);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        long transactionId = getIntent().getLongExtra(EXTRA_TRANSACTION_ID, 0L);
        viewModel = new ViewModelProvider(this).get(RefundRecordViewModel.class);

        RefundRecordAdapter adapter = new RefundRecordAdapter(new RefundRecordAdapter.Listener() {
            @Override
            public void onMarkReceived(long refundId) {
                markReceived(refundId);
            }

            @Override
            public void onEdit(@NonNull RefundRecordEntity refund) {
                showEditSheet(refund);
            }

            @Override
            public void onCancel(@NonNull RefundRecordEntity refund) {
                showCancelDialog(refund);
            }
        });
        binding.refundRecordList.setLayoutManager(new LinearLayoutManager(this));
        binding.refundRecordList.setAdapter(adapter);

        viewModel.observeRefunds(transactionId).observe(this, latest -> {
            refunds = latest;
            adapter.submitList(latest);
            binding.refundRecordEmpty.setVisibility(
                    latest == null || latest.isEmpty() ? View.VISIBLE : View.GONE);
            renderSummary();
        });
        viewModel.originalAmount().observe(this, amount -> {
            originalAmount = amount;
            renderSummary();
        });
        viewModel.loadOriginalAmount(transactionId);
    }

    /** 汇总一行：已退 / 待退直接由流水累加，账单原额来自快照。 */
    private void renderSummary() {
        Long amount = originalAmount;
        if (amount == null) {
            binding.refundSummary.setVisibility(View.GONE);
            return;
        }
        long received = 0L;
        long pending = 0L;
        List<RefundRecordEntity> latest = refunds;
        if (latest != null) {
            for (RefundRecordEntity refund : latest) {
                if (refund.isReceived()) {
                    received += refund.amount;
                } else if (refund.isPending()) {
                    pending += refund.amount;
                }
            }
        }
        binding.refundSummary.setVisibility(View.VISIBLE);
        binding.refundSummary.setText(getString(R.string.refund_record_summary_format,
                AmountUtil.format(received), AmountUtil.format(pending), AmountUtil.format(amount)));
    }

    /**
     * 以编辑模式弹出该笔退款的表单（金额 / 原因 / 到账状态都可回改）。
     *
     * <p>可改上限就地用观察到的流水算「原额 − 其他退款累计」，不读账单缓存列；
     * 仓储层在同一事务内会独立复核一遍，这里的校验只挡明显误操作。
     */
    private void showEditSheet(@NonNull RefundRecordEntity refund) {
        Long amount = originalAmount;
        if (amount == null) {
            Toast.makeText(this, R.string.refund_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        long othersReceived = 0L;
        long othersPending = 0L;
        List<RefundRecordEntity> latest = refunds;
        if (latest != null) {
            for (RefundRecordEntity other : latest) {
                if (other.id == refund.id) {
                    continue;
                }
                if (other.isReceived()) {
                    othersReceived += other.amount;
                } else if (other.isPending()) {
                    othersPending += other.amount;
                }
            }
        }
        new RefundSheetDialog(this, amount,
                RefundPolicy.remainingQuota(amount, othersReceived, othersPending), refund,
                (cents, reason, toReceived) -> viewModel.updateRefund(
                        refund.id, cents, reason, toReceived, changed -> Toast.makeText(this,
                        changed != null && changed
                                ? R.string.refund_updated : R.string.refund_failed,
                        Toast.LENGTH_SHORT).show())).show();
    }

    private void markReceived(long refundId) {
        viewModel.markReceived(refundId, changed -> {
            Toast.makeText(this, changed != null && changed
                    ? R.string.refund_record_received : R.string.refund_failed,
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void showCancelDialog(@NonNull RefundRecordEntity refund) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.refund_record_cancel_title)
                .setMessage(getString(R.string.refund_record_cancel_message,
                        AmountUtil.format(refund.amount)))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.refund_record_action_cancel, (dialog, which) -> {
                    viewModel.cancel(refund.id, changed -> Toast.makeText(this,
                            changed != null && changed
                                    ? R.string.refund_record_cancelled : R.string.refund_failed,
                            Toast.LENGTH_SHORT).show());
                })
                .show();
    }
}
