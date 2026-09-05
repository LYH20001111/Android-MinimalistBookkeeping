package com.skyanchor.bookkeeping.ui.sync;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.entity.SyncStateEntity;
import com.skyanchor.bookkeeping.data.remote.ApiDtos;
import com.skyanchor.bookkeeping.data.remote.ApiException;
import com.skyanchor.bookkeeping.data.remote.ApiService;
import com.skyanchor.bookkeeping.data.repository.AuthRepository;
import com.skyanchor.bookkeeping.sync.SyncCoordinator;
import com.skyanchor.bookkeeping.util.Callback;

import java.io.IOException;

import retrofit2.Response;

/**
 * 同步中心 ViewModel：开关状态、同步状态、待同步数量、服务器地址、首次同步确认。
 * 状态文案统一由 Activity 层资源转译（基线第 35 章），ViewModel 不产出 HTTP 细节。
 */
public class SyncCenterViewModel extends AndroidViewModel {

    /** 开启同步前的初始化检查结果（基线 7.2）。 */
    public enum Preflight {
        NOT_CONFIGURED, NOT_LOGGED_IN, EMAIL_NOT_VERIFIED, READY
    }

    private final AuthRepository authRepository;
    private final SyncCoordinator coordinator;

    private final MutableLiveData<Preflight> preflight = new MutableLiveData<>();
    private final MutableLiveData<ApiDtos.BootstrapSummaryResponse> cloudSummary =
            new MutableLiveData<>();
    private final MutableLiveData<int[]> localCounts = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);

    public SyncCenterViewModel(@NonNull Application application) {
        super(application);
        BookkeepingApp app = BookkeepingApp.get(application);
        this.authRepository = app.getAuthRepository();
        this.coordinator = app.getSyncCoordinator();
    }

    public LiveData<Preflight> preflight() {
        return preflight;
    }

    public LiveData<ApiDtos.BootstrapSummaryResponse> cloudSummary() {
        return cloudSummary;
    }

    public LiveData<int[]> localCounts() {
        return localCounts;
    }

    public LiveData<String> error() {
        return error;
    }

    public LiveData<Boolean> busy() {
        return busy;
    }

    public LiveData<SyncCoordinator.Status> status() {
        return coordinator.observeStatus();
    }

    public LiveData<Integer> pendingCount() {
        return coordinator.observePendingCount();
    }

    public LiveData<SyncStateEntity> syncState() {
        return coordinator.observeState();
    }

    public boolean isSyncEnabled() {
        return coordinator.isSyncEnabled();
    }

    public boolean isLoggedIn() {
        return authRepository.isLoggedIn();
    }

    public String accountEmail() {
        return authRepository.getAccountEmail();
    }

    public boolean isEmailVerified() {
        return authRepository.isEmailVerified();
    }

    public String serverBaseUrl() {
        return authRepository.getServerBaseUrl();
    }

    public void setServerBaseUrl(String url) {
        authRepository.setServerBaseUrl(url, result -> { });
    }

    public void setSyncEnabled(boolean enabled) {
        coordinator.setSyncEnabled(enabled);
    }

    public void requestManualSync() {
        coordinator.requestSync(true);
    }

    /** 退出登录：吊销当前设备会话，本地业务数据保留（基线 32.1）。 */
    public void logout(@NonNull Callback<Boolean> callback) {
        authRepository.logout(callback);
    }

    /** 打开开关前的初始化检查（登录 / 邮箱验证 / 服务器可达）。 */
    public void runPreflight() {
        if (!authRepository.isLoggedIn()) {
            preflight.setValue(Preflight.NOT_LOGGED_IN);
            return;
        }
        if (!authRepository.isEmailVerified()) {
            preflight.setValue(Preflight.EMAIL_NOT_VERIFIED);
            return;
        }
        ApiService api = apiService();
        if (api == null) {
            preflight.setValue(Preflight.NOT_CONFIGURED);
            return;
        }
        busy.setValue(true);
        new Thread(() -> {
            try {
                Response<ApiDtos.StatusResponse> response = api.status().execute();
                if (response.isSuccessful() && response.body() != null) {
                    preflight.postValue(response.body().emailVerified
                            ? Preflight.READY : Preflight.EMAIL_NOT_VERIFIED);
                } else {
                    preflight.postValue(Preflight.NOT_CONFIGURED);
                }
            } catch (IOException e) {
                preflight.postValue(Preflight.NOT_CONFIGURED);
            } finally {
                busy.postValue(false);
            }
        }).start();
    }

    /** 首次同步统计：本地 + 云端（基线 8.1），确认对话框展示用。 */
    public void loadBootstrapStats() {
        busy.setValue(true);
        coordinator.loadLocalCounts(new Callback<int[]>() {
            @Override
            public void onResult(int[] counts) {
                localCounts.setValue(counts);
                maybeLoadCloudSummary();
            }

            @Override
            public void onError(@NonNull Exception e) {
                busy.setValue(false);
                error.setValue(e.getMessage());
            }
        });
    }

    private void maybeLoadCloudSummary() {
        ApiService api = apiService();
        if (api == null) {
            busy.setValue(false);
            return;
        }
        new Thread(() -> {
            try {
                Response<ApiDtos.BootstrapSummaryResponse> response =
                        api.bootstrapSummary().execute();
                if (response.isSuccessful() && response.body() != null) {
                    cloudSummary.postValue(response.body());
                } else {
                    error.postValue("服务器暂时无法连接，请稍后重试");
                }
            } catch (IOException e) {
                error.postValue("服务器暂时无法连接");
            } finally {
                busy.postValue(false);
            }
        }).start();
    }

    /** 用户确认后执行首次合并（基线第 8 章）。 */
    public void confirmBootstrap(@NonNull Callback<Boolean> callback) {
        coordinator.confirmBootstrap(new Callback<Boolean>() {
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


    @androidx.annotation.Nullable
    private ApiService apiService() {
        return BookkeepingApp.get(getApplication()).getApiClient().api();
    }
}