package com.skyanchor.bookkeeping.ui.record;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.databinding.FragmentRecordBinding;
import com.skyanchor.bookkeeping.ui.adapter.TransactionListAdapter;
import com.skyanchor.bookkeeping.ui.search.SearchActivity;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.DateUtil;

/**
 * 记录页：业务日期切换 + 当天概览 + 历史账单列表 + 记一笔入口。
 *
 * <p>页面本身不持有任何统计逻辑，全部渲染数据来自 {@link RecordViewModel} 派生的
 * {@link RecordUiState}，因此新增/编辑/删除后概览与列表必然同源刷新。
 */
public class RecordFragment extends Fragment {

    private static final String TAG_CALENDAR_DIALOG = "record_calendar_dialog";

    private FragmentRecordBinding binding;
    private RecordViewModel viewModel;
    private TransactionListAdapter adapter;

    public static RecordFragment newInstance() {
        return new RecordFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentRecordBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(RecordViewModel.class);

        adapter = new TransactionListAdapter(new TransactionListAdapter.Listener() {
            @Override
            public void onTransactionClick(@NonNull TransactionItem item) {
                TransactionEditActivity.startEdit(requireContext(), item.id);
            }

            @Override
            public void onTransactionLongClick(@NonNull TransactionItem item) {
                showDeleteDialog(item);
            }
        });
        binding.transactionList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.transactionList.setAdapter(adapter);

        binding.dateButton.setOnClickListener(v -> showCalendarSummaryDialog());
        binding.searchButton.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), SearchActivity.class)));
        binding.fabAdd.setOnClickListener(v -> startAdd());
        binding.emptyState.emptyAction.setOnClickListener(v -> startAdd());

        // V2 Risk B：用 FragmentResult 接收日历弹窗选中日期，旋转/重建后不丢失。
        getChildFragmentManager().setFragmentResultListener(
                CalendarSummaryDialog.REQUEST_DATE_SELECTED, getViewLifecycleOwner(),
                (requestKey, result) -> {
                    long dayMillis = result.getLong(CalendarSummaryDialog.RESULT_KEY_DAY_MILLIS,
                            DateUtil.today());
                    viewModel.setBusinessDate(dayMillis);
                });

        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
    }

    @Override
    public void onDestroyView() {
        binding.transactionList.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }

    private void render(@Nullable RecordUiState state) {
        if (state == null) {
            return;
        }
        binding.dateButton.setText(state.businessDateLabel);
        binding.dayExpenseValue.setText(AmountUtil.format(state.daySummary.expense));
        binding.dayIncomeValue.setText(AmountUtil.format(state.daySummary.income));

        long balance = state.daySummary.balance();
        binding.dayBalanceValue.setText(AmountUtil.format(balance));
        // 结余为负（支出大于收入）时用 danger 语义色提示。
        binding.dayBalanceValue.setTextColor(ContextCompat.getColor(requireContext(),
                balance < 0L ? R.color.danger : R.color.text_primary));

        adapter.submitList(state.rows);
        boolean empty = state.isEmpty();
        binding.transactionList.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.emptyState.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void startAdd() {
        Long businessDate = viewModel.getBusinessDate().getValue();
        TransactionEditActivity.startAdd(requireContext(),
                businessDate == null ? DateUtil.today() : businessDate);
    }

    // ------------------------------------------------------------------
    // 业务日期
    // ------------------------------------------------------------------

    /** 打开带每日收支摘要的日历弹窗，替代原生 MaterialDatePicker（V1.1 目标 A）。 */
    private void showCalendarSummaryDialog() {
        Long businessDate = viewModel.getBusinessDate().getValue();
        long current = businessDate == null ? DateUtil.today() : businessDate;
        CalendarSummaryDialog.newInstance(current)
                .show(getChildFragmentManager(), TAG_CALENDAR_DIALOG);
    }

    // ------------------------------------------------------------------
    // 删除（必须二次确认，V1 基线 5.3）
    // ------------------------------------------------------------------

    private void showDeleteDialog(@NonNull TransactionItem item) {
        String message = getString(R.string.record_delete_message,
                item.displayIcon() + item.displayName(),
                AmountUtil.formatSigned(item.amount, item.isIncome()));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.record_delete_title)
                .setMessage(message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete,
                        (dialog, which) -> viewModel.deleteTransaction(item.id, this::onDeleted))
                .show();
    }

    private void onDeleted(@Nullable Boolean deleted) {
        if (binding == null) {
            return;
        }
        Snackbar.make(binding.recordRoot, R.string.record_deleted, Snackbar.LENGTH_SHORT).show();
    }
}
