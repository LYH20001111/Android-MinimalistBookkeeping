package com.skyanchor.bookkeeping.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.remote.ApiClient;
import com.skyanchor.bookkeeping.data.remote.ApiDtos;
import com.skyanchor.bookkeeping.data.remote.ApiException;
import com.skyanchor.bookkeeping.data.remote.ApiService;
import com.skyanchor.bookkeeping.data.remote.ServerConfigStore;
import com.skyanchor.bookkeeping.data.remote.TokenStore;
import com.skyanchor.bookkeeping.util.Callback;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Response;

/**
 * 认证仓库：注册 / 登录 / 退出 / 设备管理 / 注销的统一入口。
 * UI 只经由此类触网（基线第 27 章：UI 不直接调用 Retrofit/HTTP）。
 * 所有失败都以 {@link ApiException} 回调，文案已人类可读。
 */
public class AuthRepository {

    private final ApiClient apiClient;
    private final TokenStore tokenStore;
    private final ServerConfigStore serverConfigStore;

    /**
     * 认证请求专用后台执行器：HTTP 一律离开主线程（否则必然抛
     * NetworkOnMainThreadException），回调统一回主线程（与 BookkeepingRepository 同语义）。
     */
    private final java.util.concurrent.ExecutorService authIo =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    public AuthRepository(@NonNull ApiClient apiClient, @NonNull TokenStore tokenStore,
                          @NonNull ServerConfigStore serverConfigStore) {
        this.apiClient = apiClient;
        this.tokenStore = tokenStore;
        this.serverConfigStore = serverConfigStore;
    }

    public boolean isLoggedIn() {
        return tokenStore.isLoggedIn();
    }

    @Nullable
    public String getAccountEmail() {
        return tokenStore.getAccountEmail();
    }

    public boolean isEmailVerified() {
        return tokenStore.isEmailVerified();
    }

    public void setServerBaseUrl(@Nullable String url, @NonNull Callback<Boolean> callback) {
        serverConfigStore.setBaseUrl(url);
        apiClient.resetBaseUrl();
        callback.onResult(serverConfigStore.isConfigured());
    }

    @Nullable
    public String getServerBaseUrl() {
        return serverConfigStore.getBaseUrl();
    }

    public void register(@NonNull String email, @NonNull String password,
                         @NonNull Callback<Boolean> callback) {
        ApiService api = apiService();
        if (api == null) {
            callback.onError(notConfigured());
            return;
        }
        ApiDtos.RegisterRequest request = new ApiDtos.RegisterRequest(
                email.trim().toLowerCase(Locale.US), password);
        run(api.register(request), ok -> true, callback);
    }

    public void login(@NonNull String email, @NonNull String password,
                      @NonNull String deviceName, @NonNull String appVersion,
                      @NonNull Callback<ApiDtos.AuthResponse> callback) {
        ApiService api = apiService();
        if (api == null) {
            callback.onError(notConfigured());
            return;
        }
        ApiDtos.DeviceInfo device = new ApiDtos.DeviceInfo(
                tokenStore.getOrCreateDeviceId(), deviceName, "android", appVersion);
        ApiDtos.LoginRequest request = new ApiDtos.LoginRequest(
                email.trim().toLowerCase(Locale.US), password, device);
        run(api.login(request), response -> {
            tokenStore.updateTokens(response.accessToken, response.refreshToken,
                    response.email, response.emailVerified,
                    response.device != null ? response.device.id : -1);
            return response;
        }, callback);
    }

    public void resendVerification(@NonNull String email,
                                   @NonNull Callback<Boolean> callback) {
        ApiService api = apiService();
        if (api == null) {
            callback.onError(notConfigured());
            return;
        }
        // resend 接口复用 LoginRequest 结构，但仅使用 email 字段，不携带凭据
        ApiDtos.LoginRequest request = new ApiDtos.LoginRequest(
                email.trim().toLowerCase(Locale.US), "", null);
        run(api.resendVerification(request), ok -> true, callback);
    }

    /** 退出当前设备：吊销服务端会话 + 清本地凭据（本地业务数据保留）。 */
    public void logout(@NonNull Callback<Boolean> callback) {
        ApiService api = apiService();
        if (api == null) {
            tokenStore.clearCredentials();
            callback.onResult(true);
            return;
        }
        run(api.logout(), ok -> {
            tokenStore.clearCredentials();
            return true;
        }, callback);
    }

    /** 退出全部设备（云端数据不删除，基线 32.2）。 */
    public void logoutAll(@NonNull Callback<Boolean> callback) {
        ApiService api = apiService();
        if (api == null) {
            tokenStore.clearCredentials();
            callback.onResult(true);
            return;
        }
        run(api.logoutAll(), ok -> {
            tokenStore.clearCredentials();
            return true;
        }, callback);
    }

    public void listDevices(@NonNull Callback<List<ApiDtos.DeviceDto>> callback) {
        ApiService api = apiService();
        if (api == null) {
            callback.onError(notConfigured());
            return;
        }
        run(api.listDevices(), devices -> devices, callback);
    }

    public void revokeDevice(long deviceRowId, @NonNull Callback<Boolean> callback) {
        ApiService api = apiService();
        if (api == null) {
            callback.onError(notConfigured());
            return;
        }
        run(api.revokeDevice(deviceRowId), ok -> true, callback);
    }

    /** 账号注销：服务端删除云端数据后，本地凭据清除（本地数据是否清空由 UI 单独询问）。 */
    public void deleteAccount(@NonNull String password, @NonNull Callback<Boolean> callback) {
        ApiService api = apiService();
        if (api == null) {
            callback.onError(notConfigured());
            return;
        }
        run(api.deleteAccount(new ApiDtos.DeleteAccountRequest(password)), ok -> {
            tokenStore.clearCredentials();
            return true;
        }, callback);
    }

    // ===== 内部 =====

    @Nullable
    private ApiService apiService() {
        return apiClient.api();
    }

    private static ApiException notConfigured() {
        return new ApiException(ApiException.NOT_CONFIGURED, 0, "服务器地址未配置");
    }

    /** 后台执行 retrofit Call，错误统一转译，回调回主线程。 */
    private <S, R> void run(Call<S> call, ResultMapper<S, R> mapper,
                            Callback<R> callback) {
        authIo.execute(() -> {
            R result = null;
            Exception error = null;
            try {
                Response<S> response = call.execute();
                if (response.isSuccessful()) {
                    result = mapper.map(response.body());
                } else {
                    error = ApiClient.toApiError(response);
                }
            } catch (IOException e) {
                error = ApiClient.toNetworkError(e);
            } catch (Exception e) {
                error = new ApiException("UNKNOWN", 0,
                        e.getMessage() == null ? "未知错误" : e.getMessage());
            }
            R finalResult = result;
            Exception finalError = error;
            mainHandler.post(() -> {
                if (finalError != null) {
                    callback.onError(finalError);
                } else {
                    callback.onResult(finalResult);
                }
            });
        });
    }

    private interface ResultMapper<S, R> {
        R map(@Nullable S body);
    }
}
