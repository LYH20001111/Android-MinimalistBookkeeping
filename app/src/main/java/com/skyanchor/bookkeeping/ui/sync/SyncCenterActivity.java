package com.skyanchor.bookkeeping.ui.sync;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.SyncStateEntity;
import com.skyanchor.bookkeeping.data.remote.ApiDtos;
import com.skyanchor.bookkeeping.databinding.ActivitySyncCenterBinding;
import com.skyanchor.bookkeeping.sync.SyncCoordinator;
import com.skyanchor.bookkeeping.ui.auth.LoginActivity;
import com.skyanchor.bookkeeping.util.Callback;
import com.skyanchor.bookkeeping.util.InsetsUtil;

/**
 * 同步中心（基线第 20 章）：开关、服务器地址、连接状态、最近同步、待同步、冲突、立即同步。
 * 开启流程严格执行「初始化检查 → 首次同步统计 → 用户确认」（基线第 7、8 章）。
 */
public class SyncCenterActivity extends AppCompatActivity {

    private static final int REQ_LOGIN = 1001;

    private ActivitySyncCenterBinding binding;
    private SyncCenterViewModel viewModel;

    public static Intent newIntent(@NonNull android.content.Context context) {
        return new Intent(context, SyncCenterActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySyncCenterBinding.inflate(getLayoutInflater());
        // XML 初始文本是带 %1$s/%1$d 占位符的格式串，先填入默认值，避免 Room 异步发射前显示原始占位符
        binding.lastSyncText.setText(getString(R.string.sync_last_format,
                getString(R.string.sync_never)));
        binding.pendingText.setText(getString(R.string.sync_pending_format, 0));
        binding.conflictText.setText(getString(R.string.sync_conflict_format, 0));
        setContentView(binding.getRoot());
        InsetsUtil.applyTopAndHorizontalPadding(binding.syncRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        binding.toolbar.setNavigationOnClickListener(v -> finish());

        viewModel = new ViewModelProvider(this).get(SyncCenterViewModel.class);

        binding.syncSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                onSwitchChanged(isChecked));
        binding.syncNowButton.setOnClickListener(v -> {
            viewModel.requestManualSync();
            Snackbar.make(binding.syncRoot, R.string.sync_started, Snackbar.LENGTH_SHORT).show();
        });
        binding.saveServerButton.setOnClickListener(v -> saveServerUrl());
        binding.deviceManageRow.setOnClickListener(v ->
                startActivity(DeviceManageActivity.newIntent(this)));
        binding.logoutButton.setOnClickListener(v -> confirmLogout());

        observe();
        renderAccountSection();
        renderServerUrl();
    }

    private void observe() {
        viewModel.status().observe(this, this::renderStatus);
        viewModel.pendingCount().observe(this, count ->
                binding.pendingText.setText(getString(R.string.sync_pending_format,
                        count == null ? 0 : count)));
        viewModel.syncState().observe(this, state -> {
            if (state == null) {
                // 无持久化同步状态（如首次安装尚未触发同步）：根据服务器地址配置显示状态，
                // 并补齐最近同步/最近冲突默认文案，否则布局里的 %1$s 占位符会一直显示
                binding.lastSyncText.setText(getString(R.string.sync_last_format,
                        getString(R.string.sync_never)));
                binding.conflictText.setText(getString(R.string.sync_conflict_format, 0));
                binding.serverStateText.setText(serverStateText(null));
                return;
            }
            binding.syncSwitch.setOnCheckedChangeListener(null);
            binding.syncSwitch.setChecked(state.syncEnabled);
            binding.syncSwitch.setOnCheckedChangeListener((b, checked) ->
                    onSwitchChanged(checked));
            binding.lastSyncText.setText(getString(R.string.sync_last_format,
                    lastSyncText(state.lastSyncAt)));
            binding.conflictText.setText(getString(R.string.sync_conflict_format,
                    state.conflictCount));
            binding.serverStateText.setText(serverStateText(state.status));
        });
        viewModel.preflight().observe(this, this::onPreflight);
        viewModel.localCounts().observe(this, counts -> maybeShowBootstrapConfirm());
        viewModel.cloudSummary().observe(this, summary -> maybeShowBootstrapConfirm());
        viewModel.error().observe(this, message -> {
            if (message != null) {
                Snackbar.make(binding.syncRoot, message, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    // ===== 开关：先初始化检查，通过并经用户确认后才真正打开（基线 7.2 / 8.1） =====

    private void onSwitchChanged(boolean checked) {
        if (!checked) {
            // 关闭 = 只暂停本设备同步，不清数据、不退登录（基线 7.3）
            viewModel.setSyncEnabled(false);
            return;
        }
        // 先回弹开关，等检查通过 + 用户确认后再真正打开
        setSwitchSilently(false);

        if (!viewModel.isLoggedIn()) {
            startActivityForResult(LoginActivity.newIntent(this), REQ_LOGIN);
            return;
        }
        viewModel.runPreflight();
    }

    private void setSwitchSilently(boolean checked) {
        binding.syncSwitch.setOnCheckedChangeListener(null);
        binding.syncSwitch.setChecked(checked);
        binding.syncSwitch.setOnCheckedChangeListener((b, c) -> onSwitchChanged(c));
    }

    private void onPreflight(SyncCenterViewModel.Preflight result) {
        if (result == null) {
            return;
        }
        switch (result) {
            case NOT_LOGGED_IN:
                startActivityForResult(LoginActivity.newIntent(this), REQ_LOGIN);
                break;
            case EMAIL_NOT_VERIFIED:
                Snackbar.make(binding.syncRoot, R.string.sync_error_email_unverified,
                        Snackbar.LENGTH_LONG).show();
                break;
            case NOT_CONFIGURED:
                Snackbar.make(binding.syncRoot, R.string.sync_error_unreachable,
                        Snackbar.LENGTH_LONG).show();
                break;
            case READY:
                viewModel.loadBootstrapStats();
                break;
        }
    }

    // ===== 首次同步确认（基线第 8 章：不允许未经确认覆盖数据） =====

    private void maybeShowBootstrapConfirm() {
        ApiDtos.BootstrapSummaryResponse cloud = viewModel.cloudSummary().getValue();
        int[] local = viewModel.localCounts().getValue();
        if (cloud == null || local == null || Boolean.TRUE.equals(viewModel.busy().getValue())) {
            return;
        }
        String message;
        if (!cloud.hasCloudData) {
            message = getString(R.string.bootstrap_message_local_to_cloud,
                    local[0], local[1], local[2], local[3], local[4]);
        } else {
            ApiDtos.BootstrapSummaryResponse.Counts c = cloud.counts;
            message = getString(R.string.bootstrap_message_merge,
                    local[0], local[1], local[2], local[3], local[4],
                    c.transaction, c.account, c.category, c.budget, c.recurring);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bootstrap_confirm_title)
                .setMessage(message)
                .setNegativeButton(R.string.bootstrap_cancel, null)
                .setPositiveButton(R.string.bootstrap_confirm, (d, w) ->
                        viewModel.confirmBootstrap(new Callback<Boolean>() {
                            @Override
                            public void onResult(Boolean result) {
                                viewModel.setSyncEnabled(true);
                                Snackbar.make(binding.syncRoot,
                                        result != null && result
                                                ? R.string.bootstrap_done
                                                : R.string.sync_error_unreachable,
                                        Snackbar.LENGTH_LONG).show();
                            }

                            @Override
                            public void onError(@NonNull Exception e) {
                                Snackbar.make(binding.syncRoot, e.getMessage(),
                                        Snackbar.LENGTH_LONG).show();
                            }
                        }))
                .setCancelable(false)
                .show();
    }

    // ===== 其他区块 =====

    private void saveServerUrl() {
        String url = binding.serverUrlInput.getText() == null
                ? "" : binding.serverUrlInput.getText().toString().trim();
        if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            Snackbar.make(binding.syncRoot, R.string.sync_error_url_format,
                    Snackbar.LENGTH_LONG).show();
            return;
        }
        viewModel.setServerBaseUrl(url);
        // 立即基于 URL 更新显示（乐观），再异步探测验证，syncState observer 会最终修正
        refreshServerState();
        viewModel.checkServerStatus();
        Snackbar.make(binding.syncRoot, R.string.sync_server_saved, Snackbar.LENGTH_SHORT).show();
    }

    /** 根据当前持久化状态和服务器地址配置刷新服务器状态显示。 */
    private void refreshServerState() {
        SyncStateEntity state = viewModel.syncState().getValue();
        String status = state != null ? state.status : null;
        binding.serverStateText.setText(serverStateText(status));
    }

    private void confirmLogout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sync_logout_title)
                .setMessage(R.string.sync_logout_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.sync_logout_confirm, (d, w) ->
                        viewModel.logout(new Callback<Boolean>() {
                            @Override
                            public void onResult(Boolean result) {
                                setSwitchSilently(false);
                                renderAccountSection();
                            }
                        }))
                .show();
    }

    private void renderAccountSection() {
        boolean loggedIn = viewModel.isLoggedIn();
        binding.accountCard.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
        binding.notLoggedInText.setVisibility(loggedIn ? View.GONE : View.VISIBLE);
        binding.notLoggedInText.setOnClickListener(loggedIn ? null : v ->
                startActivityForResult(LoginActivity.newIntent(this), REQ_LOGIN));
        if (loggedIn) {
            binding.accountEmailText.setText(viewModel.accountEmail());
            binding.accountVerifiedText.setText(viewModel.isEmailVerified()
                    ? R.string.sync_email_verified
                    : R.string.sync_email_unverified);
        }
    }

    private void renderServerUrl() {
        String url = viewModel.serverBaseUrl();
        binding.serverUrlInput.setText(url == null ? "" : url);
    }

    private void renderStatus(SyncCoordinator.Status status) {
        if (status == null) {
            binding.statusText.setText(R.string.sync_status_idle);
            return;
        }
        switch (status) {
            case SYNCING:
                binding.statusText.setText(R.string.sync_status_syncing);
                break;
            case WAITING_NETWORK:
                binding.statusText.setText(R.string.sync_status_waiting_network);
                break;
            case WAITING_RETRY:
                binding.statusText.setText(R.string.sync_status_waiting_retry);
                break;
            case AUTH_REQUIRED:
                binding.statusText.setText(R.string.sync_status_auth_required);
                break;
            case SERVER_UNAVAILABLE:
                binding.statusText.setText(R.string.sync_status_server_unavailable);
                break;
            case SUCCESS:
                binding.statusText.setText(R.string.sync_status_success);
                break;
            case ERROR:
                binding.statusText.setText(R.string.sync_status_error);
                break;
            case IDLE:
            default:
                binding.statusText.setText(R.string.sync_status_idle);
                break;
        }
    }

    private String serverStateText(String status) {
        boolean unavailable = SyncCoordinator.Status.SERVER_UNAVAILABLE.name().equals(status)
                || SyncCoordinator.Status.WAITING_NETWORK.name().equals(status);
        // 如果服务器地址未配置，服务器不可能在线（基线第 2 章：本地优先，服务器为可选增强）
        if (!unavailable) {
            String url = viewModel.serverBaseUrl();
            if (url == null || url.isEmpty()) {
                unavailable = true;
            }
        }
        return getString(unavailable
                ? R.string.sync_server_unavailable_short
                : R.string.sync_server_online_short);
    }

    private String lastSyncText(long lastSyncAt) {
        if (lastSyncAt <= 0) {
            return getString(R.string.sync_never);
        }
        long diff = System.currentTimeMillis() - lastSyncAt;
        long minutes = diff / 60_000;
        if (minutes < 1) {
            return getString(R.string.sync_just_now);
        }
        if (minutes < 60) {
            return getString(R.string.sync_minutes_ago, minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return getString(R.string.sync_hours_ago, hours);
        }
        return getString(R.string.sync_days_ago, hours / 24);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LOGIN && resultCode == RESULT_OK) {
            renderAccountSection();
            viewModel.runPreflight();
        }
    }
}
