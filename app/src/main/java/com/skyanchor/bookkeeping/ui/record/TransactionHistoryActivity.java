package com.skyanchor.bookkeeping.ui.record;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.skyanchor.bookkeeping.databinding.ActivityTransactionHistoryBinding;
import com.skyanchor.bookkeeping.ui.adapter.TransactionEditLogAdapter;
import com.skyanchor.bookkeeping.util.InsetsUtil;

/**
 * 账单编辑记录（V3.3）：从编辑账单页右上角三点菜单进入，展示当前账单的
 * 创建时间与每次修改的字段级变更（最新在前）。
 *
 * <p>功能上线前已存在的账单没有日志行，显示「暂无编辑记录」空态。
 */
public class TransactionHistoryActivity extends AppCompatActivity {

    /** 待查看的账单 id。 */
    public static final String EXTRA_TRANSACTION_ID = "extra_transaction_id";

    private ActivityTransactionHistoryBinding binding;

    public static void start(@NonNull Context context, long transactionId) {
        Intent intent = new Intent(context, TransactionHistoryActivity.class);
        intent.putExtra(EXTRA_TRANSACTION_ID, transactionId);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityTransactionHistoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsUtil.applyTopAndHorizontalPadding(binding.historyRoot);
        InsetsUtil.syncSystemBarAppearance(this);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        long transactionId = getIntent().getLongExtra(EXTRA_TRANSACTION_ID, 0L);

        TransactionHistoryViewModel viewModel =
                new ViewModelProvider(this).get(TransactionHistoryViewModel.class);
        TransactionEditLogAdapter adapter = new TransactionEditLogAdapter();
        binding.historyList.setLayoutManager(new LinearLayoutManager(this));
        binding.historyList.setAdapter(adapter);

        viewModel.logs().observe(this, logs -> {
            adapter.submitList(logs);
            binding.emptyText.setVisibility(
                    logs == null || logs.isEmpty() ? View.VISIBLE : View.GONE);
        });
        viewModel.load(transactionId);
    }
}
