package com.skyanchor.bookkeeping.ui.sync;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.remote.ApiDtos;
import com.skyanchor.bookkeeping.databinding.ItemDeviceBinding;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 设备列表适配器（基线第 19 章）：当前设备标记「本机」，可单设备退出。 */
public class DeviceAdapter extends ListAdapter<ApiDtos.DeviceDto, DeviceAdapter.Holder> {

    public interface Listener {
        void onRevoke(@NonNull ApiDtos.DeviceDto device);
    }

    private final Listener listener;
    private final SimpleDateFormat format =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    public DeviceAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<ApiDtos.DeviceDto> DIFF =
            new DiffUtil.ItemCallback<ApiDtos.DeviceDto>() {
                @Override
                public boolean areItemsTheSame(@NonNull ApiDtos.DeviceDto a,
                                               @NonNull ApiDtos.DeviceDto b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull ApiDtos.DeviceDto a,
                                                  @NonNull ApiDtos.DeviceDto b) {
                    return a.id == b.id
                            && a.lastSeenAt == b.lastSeenAt
                            && a.revoked == b.revoked
                            && equals(a.deviceName, b.deviceName)
                            && equals(a.appVersion, b.appVersion);
                }

                private boolean equals(Object a, Object b) {
                    return a == null ? b == null : a.equals(b);
                }
            };

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemDeviceBinding binding =
                ItemDeviceBinding.inflate(LayoutInflater.from(parent.getContext()),
                        parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ApiDtos.DeviceDto device = getItem(position);
        holder.bind(device, listener, format);
    }

    class Holder extends RecyclerView.ViewHolder {

        private final ItemDeviceBinding binding;

        Holder(@NonNull ItemDeviceBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull ApiDtos.DeviceDto device, @NonNull Listener listener,
                  @NonNull SimpleDateFormat format) {
            String name = device.deviceName == null || device.deviceName.isEmpty()
                    ? binding.getRoot().getContext().getString(R.string.device_unknown_name)
                    : device.deviceName;
            binding.deviceName.setText(device.current
                    ? binding.getRoot().getContext().getString(R.string.device_current, name)
                    : name);
            String meta = binding.getRoot().getContext().getString(
                    R.string.device_meta_format,
                    device.platform == null ? "" : device.platform,
                    device.appVersion == null ? "" : device.appVersion,
                    format.format(new Date(device.lastSeenAt)));
            binding.deviceMeta.setText(meta);
            binding.revokeButton.setVisibility(device.current || device.revoked
                    ? View.GONE : View.VISIBLE);
            binding.currentBadge.setVisibility(device.current ? View.VISIBLE : View.GONE);
            binding.revokeButton.setOnClickListener(v -> listener.onRevoke(device));
        }
    }
}
