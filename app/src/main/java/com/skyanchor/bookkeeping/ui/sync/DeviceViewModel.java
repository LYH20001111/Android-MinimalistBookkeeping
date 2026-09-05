package com.skyanchor.bookkeeping.ui.sync;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.remote.ApiDtos;
import com.skyanchor.bookkeeping.data.repository.AuthRepository;
import com.skyanchor.bookkeeping.util.Callback;

import java.util.List;

/** 设备管理 ViewModel（基线第 19 章）：列表 / 单设备退出 / 全部设备退出。 */
public class DeviceViewModel extends AndroidViewModel {

    private final AuthRepository authRepository;
    private final MutableLiveData<List<ApiDtos.DeviceDto>> devices = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);

    public DeviceViewModel(@NonNull Application application) {
        super(application);
        authRepository = BookkeepingApp.get(application).getAuthRepository();
    }

    public LiveData<List<ApiDtos.DeviceDto>> devices() {
        return devices;
    }

    public LiveData<String> error() {
        return error;
    }

    public LiveData<Boolean> busy() {
        return busy;
    }

    public void refresh() {
        if (Boolean.TRUE.equals(busy.getValue())) {
            return;
        }
        busy.setValue(true);
        authRepository.listDevices(new Callback<List<ApiDtos.DeviceDto>>() {
            @Override
            public void onResult(List<ApiDtos.DeviceDto> result) {
                busy.setValue(false);
                devices.setValue(result);
            }

            @Override
            public void onError(@NonNull Exception e) {
                busy.setValue(false);
                error.setValue(e.getMessage());
            }
        });
    }

    public void revoke(long deviceRowId, @NonNull Callback<Boolean> callback) {
        authRepository.revokeDevice(deviceRowId, new Callback<Boolean>() {
            @Override
            public void onResult(Boolean result) {
                callback.onResult(result);
                refresh();
            }

            @Override
            public void onError(@NonNull Exception e) {
                callback.onError(e);
            }
        });
    }

    public void logoutAll(@NonNull Callback<Boolean> callback) {
        authRepository.logoutAll(new Callback<Boolean>() {
            @Override
            public void onResult(Boolean result) {
                callback.onResult(result);
            }

            @Override
            public void onError(@NonNull Exception e) {
                callback.onError(e);
            }
        });
    }
}
