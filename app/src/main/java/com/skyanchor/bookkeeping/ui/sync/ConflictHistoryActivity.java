package com.skyanchor.bookkeeping.ui.sync;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.snackbar.Snackbar;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.databinding.ActivityConflictHistoryBinding;
import com.skyanchor.bookkeeping.util.InsetsUtil;

/**
 * 冲突历史（V3.1 基线第 26/27 章）：最近冲突摘要列表。
 * 仍然遵循「自动收敛 + 事后可查」，不提供版本选择弹窗，不阻断任何用户操作。
 */
public class ConflictHistoryActivity extends AppCompatActivity {

    private ActivityConflictHistoryBinding binding;
    private ConflictHistoryViewModel viewModel;
    private ConflictHistoryAdapter adapter;

    public static Intent newIntent(@NonNull Context context) {
        return new Intent(context, ConflictHistoryActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityConflictHistoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsUtil.applyTopAndHorizontalPadding(binding.conflictRoot);
        InsetsUtil.syncSystemBarAppearance(this);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        viewModel = new ViewModelProvider(this).get(ConflictHistoryViewModel.class);
        adapter = new ConflictHistoryAdapter();
        binding.conflictList.setLayoutManager(new LinearLayoutManager(this));
        binding.conflictList.setAdapter(adapter);

        viewModel.rows().observe(this, rows -> {
            adapter.submitList(rows);
            binding.emptyText.setVisibility(
                    rows == null || rows.isEmpty() ? View.VISIBLE : View.GONE);
        });
        viewModel.busy().observe(this, busy ->
                binding.emptyText.setVisibility(Boolean.TRUE.equals(busy)
                        ? View.GONE : binding.emptyText.getVisibility()));
        viewModel.error().observe(this, message -> {
            if (message != null) {
                Snackbar.make(binding.conflictRoot, message, Snackbar.LENGTH_LONG).show();
            }
        });
        viewModel.load();
    }
}
