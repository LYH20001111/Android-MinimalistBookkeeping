package com.skyanchor.bookkeeping.ui.sync;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.remote.ApiDtos;
import com.skyanchor.bookkeeping.databinding.ActivityDeviceManageBinding;
import com.skyanchor.bookkeeping.util.Callback;
import com.skyanchor.bookkeeping.util.InsetsUtil;

/**
 * 设备管理（基线第 19 章）：查看当前 / 其他设备、最后活跃时间、单设备退出、全部设备退出。
 */
public class DeviceManageActivity extends AppCompatActivity {

    private ActivityDeviceManageBinding binding;
    private DeviceViewModel viewModel;
    private DeviceAdapter adapter;

    public static Intent newIntent(@NonNull android.content.Context context) {
        return new Intent(context, DeviceManageActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDeviceManageBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsUtil.applyTopAndHorizontalPadding(binding.deviceRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        binding.toolbar.setNavigationOnClickListener(v -> finish());

        viewModel = new ViewModelProvider(this).get(DeviceViewModel.class);

        adapter = new DeviceAdapter(this::confirmRevoke);
        binding.deviceList.setLayoutManager(new LinearLayoutManager(this));
        binding.deviceList.setAdapter(adapter);

        binding.logoutAllButton.setOnClickListener(v -> confirmLogoutAll());

        viewModel.devices().observe(this, devices -> {
            adapter.submitList(devices);
            binding.emptyText.setVisibility(
                    devices == null || devices.isEmpty() ? View.VISIBLE : View.GONE);
        });
        viewModel.error().observe(this, message -> {
            if (message != null) {
                Snackbar.make(binding.deviceRoot, message, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.refresh();
    }

    private void confirmRevoke(@NonNull ApiDtos.DeviceDto device) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.device_revoke_title)
                .setMessage(getString(R.string.device_revoke_message,
                        device.deviceName == null ? "" : device.deviceName))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.device_revoke_confirm, (d, w) ->
                        viewModel.revoke(device.id, new Callback<Boolean>() {
                            @Override
                            public void onResult(Boolean result) {
                                Snackbar.make(binding.deviceRoot,
                                        R.string.device_revoked, Snackbar.LENGTH_SHORT).show();
                            }

                            @Override
                            public void onError(@NonNull Exception e) {
                                Snackbar.make(binding.deviceRoot, e.getMessage(),
                                        Snackbar.LENGTH_LONG).show();
                            }
                        }))
                .show();
    }

    private void confirmLogoutAll() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.device_logout_all_title)
                .setMessage(R.string.device_logout_all_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.device_logout_all_confirm, (d, w) ->
                        viewModel.logoutAll(new Callback<Boolean>() {
                            @Override
                            public void onResult(Boolean result) {
                                // 全部设备退出含本机：本地会话已清，直接返回
                                finish();
                            }

                            @Override
                            public void onError(@NonNull Exception e) {
                                Snackbar.make(binding.deviceRoot, e.getMessage(),
                                        Snackbar.LENGTH_LONG).show();
                            }
                        }))
                .show();
    }
}
