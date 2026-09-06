package com.skyanchor.bookkeeping.ui.sync;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.SyncEventEntity;
import com.skyanchor.bookkeeping.data.entity.SyncStateEntity;
import com.skyanchor.bookkeeping.databinding.ActivitySyncDiagnosticsBinding;
import com.skyanchor.bookkeeping.util.InsetsUtil;

import java.util.List;

/**
 * 高级诊断（V3.1 基线第 24 章）：Server / API / 协议版本、最近一轮 Push/Pull 细分、
 * 冲突与错误、最近 50 次同步事件摘要。仅用于排障，不进入普通首页；
 * 展示内容全部来自本地诊断存储，不含 Token / 密码（基线第 45 章）。
 */
public class SyncDiagnosticsActivity extends AppCompatActivity {

    private ActivitySyncDiagnosticsBinding binding;
    private SyncDiagnosticsViewModel viewModel;
    private SyncEventAdapter adapter;

    public static Intent newIntent(@NonNull Context context) {
        return new Intent(context, SyncDiagnosticsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySyncDiagnosticsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsUtil.applyTopAndHorizontalPadding(binding.diagRoot);
        InsetsUtil.syncSystemBarAppearance(this);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        viewModel = new ViewModelProvider(this).get(SyncDiagnosticsViewModel.class);
        adapter = new SyncEventAdapter();
        binding.eventList.setLayoutManager(new LinearLayoutManager(this));
        binding.eventList.setAdapter(adapter);

        viewModel.syncState().observe(this, this::renderState);
        viewModel.events().observe(this, this::renderEvents);
        renderServerSection();
    }

    private void renderServerSection() {
        String url = BookkeepingApp.get(this).getServerConfigStore().getBaseUrl();
        binding.diagServerText.setText(getString(R.string.sync_diag_server_section)
                + "\n" + (url == null || url.isEmpty()
                ? getString(R.string.sync_error_url_format) : url)
                + "\n" + getString(R.string.sync_health_protocol_format, 1, 1));
    }

    private void renderState(@Nullable SyncStateEntity state) {
        if (state == null) {
            return;
        }
        binding.diagPushPullText.setText(getString(R.string.sync_diag_push_count_format,
                state.lastPushCount) + "\n" + getString(R.string.sync_diag_pull_format,
                state.lastPullCount) + "\n" + getString(R.string.sync_diag_duration_format,
                state.lastDurationMs));
        binding.diagConflictText.setText(getString(R.string.sync_conflict_format,
                state.conflictCount) + "\n" + getString(R.string.sync_diag_epoch_format,
                state.recoveryEpoch));
        binding.diagErrorText.setText(getString(R.string.sync_diag_last_error_format,
                state.lastError == null ? getString(R.string.sync_diag_no_error)
                        : state.lastError));
    }

    private void renderEvents(@Nullable List<SyncEventEntity> events) {
        adapter.submitList(events);
        binding.eventEmptyText.setVisibility(
                events == null || events.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
    }
}
