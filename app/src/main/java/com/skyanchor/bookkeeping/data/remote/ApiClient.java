package com.skyanchor.bookkeeping.data.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Authenticator;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * HTTP 组合根：OkHttp + Retrofit 单例。
 *
 * <p>鉴权链路：请求注入 Authorization + 版本头 → 401 时由 Authenticator 自动用
 * refresh token 换新 access token 并重放一次；refresh 失败清凭据并回调
 * {@link AuthStateListener}（同步状态转 AUTH_REQUIRED，本地功能不受影响）。
 * 日志拦截器只记方法与路径，不记录头与请求体（基线第 45 章：严禁泄露 Token）。
 */
public class ApiClient {

    /** API Version / Sync Protocol Version（与服务端 ApiVersionFilter 对齐）。V3.2 升级到 2。 */
    public static final int API_VERSION = 2;
    public static final int SYNC_PROTOCOL_VERSION = 2;

    public interface AuthStateListener {
        /** refresh token 已失效，需要重新登录；不影响本地使用。 */
        void onAuthRequired();
    }

    private final TokenStore tokenStore;
    private final ServerConfigStore serverConfigStore;
    private final AuthStateListener authStateListener;

    private volatile ApiService apiService;
    private volatile boolean refreshing;

    public ApiClient(@NonNull TokenStore tokenStore,
                     @NonNull ServerConfigStore serverConfigStore,
                     @Nullable AuthStateListener authStateListener) {
        this.tokenStore = tokenStore;
        this.serverConfigStore = serverConfigStore;
        this.authStateListener = authStateListener;
    }

    /**
     * 获取 ApiService。返回 null 表示服务器地址未配置——调用方应视为
     * 「服务器暂不可用」，而非错误。
     */
    @Nullable
    public synchronized ApiService api() {
        String baseUrl = serverConfigStore.getBaseUrl();
        if (baseUrl == null) {
            return null;
        }
        if (apiService == null) {
            apiService = buildRetrofit(baseUrl).create(ApiService.class);
        }
        return apiService;
    }

    /** 服务器地址变更后重建 Retrofit。 */
    public synchronized void resetBaseUrl() {
        apiService = null;
    }

    private Retrofit buildRetrofit(String baseUrl) {
        Interceptor headerInterceptor = chain -> {
            Request original = chain.request();
            Request.Builder builder = original.newBuilder()
                    .header("X-Api-Version", String.valueOf(API_VERSION))
                    .header("X-Sync-Protocol-Version", String.valueOf(SYNC_PROTOCOL_VERSION))
                    .header("X-Device-Id", tokenStore.getOrCreateDeviceId());
            String access = tokenStore.getAccessToken();
            if (access != null) {
                builder.header("Authorization", "Bearer " + access);
            }
            return chain.proceed(builder.build());
        };

        Authenticator authenticator = (route, response) -> {
            if (refreshing) {
                return null; // 已有刷新在途，放弃重放（下轮同步会重试）
            }
            String refreshToken = tokenStore.getRefreshToken();
            if (refreshToken == null) {
                return null;
            }
            refreshing = true;
            String newAccess = null;
            String newRefresh = null;
            try {
                ApiDtos.RefreshResponse refreshed = refreshBlocking(refreshToken);
                newAccess = refreshed.accessToken;
                newRefresh = refreshed.refreshToken;
            } catch (Exception e) {
                // refresh 失败：进入 AUTH_REQUIRED；本地记账与队列完全不受影响
                tokenStore.clearCredentials();
                if (authStateListener != null) {
                    authStateListener.onAuthRequired();
                }
                return null;
            } finally {
                refreshing = false;
            }
            tokenStore.updateTokens(newAccess, newRefresh, null, true, -1);
            return response.request().newBuilder()
                    .header("Authorization", "Bearer " + newAccess)
                    .build();
        };

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(headerInterceptor)
                .authenticator(authenticator)
                .build();

        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(new Gson()))
                .build();
    }

    /** 独立通道执行 refresh（无鉴权头、无重试），避免与 Authenticator 自环。 */
    private ApiDtos.RefreshResponse refreshBlocking(String refreshToken) throws ApiException {
        String baseUrl = serverConfigStore.getBaseUrl();
        if (baseUrl == null) {
            throw new ApiException(ApiException.NOT_CONFIGURED, 0, "服务器地址未配置");
        }
        OkHttpClient bare = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        com.google.gson.Gson gson = new com.google.gson.Gson();
        okhttp3.MediaType json = okhttp3.MediaType.parse("application/json; charset=utf-8");
        okhttp3.RequestBody body = okhttp3.RequestBody.create(
                gson.toJson(new ApiDtos.RefreshRequest(refreshToken)), json);
        Request request = new Request.Builder()
                .url(baseUrl + "api/v1/auth/refresh")
                .header("X-Api-Version", String.valueOf(API_VERSION))
                .header("X-Sync-Protocol-Version", String.valueOf(SYNC_PROTOCOL_VERSION))
                .post(body)
                .build();
        try (okhttp3.Response response = bare.newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (response.code() == 401 || response.code() == 403) {
                throw new ApiException(ApiException.AUTH_REQUIRED, response.code(), "refresh 失效");
            }
            if (!response.isSuccessful()) {
                throw new ApiException(ApiException.SERVER_UNAVAILABLE, response.code(),
                        "refresh 失败: " + response.code());
            }
            return gson.fromJson(text, ApiDtos.RefreshResponse.class);
        } catch (IOException e) {
            throw new ApiException(ApiException.NETWORK, 0, "refresh 网络失败");
        }
    }

    /** 将 retrofit Call 的失败体解析为统一 ApiException。 */
    @NonNull
    public static ApiException toApiError(retrofit2.Response<?> response) {
        String code = "SERVER_ERROR";
        String message = "请求失败";
        try {
            if (response.errorBody() != null) {
                String raw = response.errorBody().string();
                ApiDtos.ErrorEnvelope envelope = new Gson().fromJson(raw, ApiDtos.ErrorEnvelope.class);
                if (envelope != null && envelope.error != null) {
                    code = envelope.error.code != null ? envelope.error.code : code;
                    message = envelope.error.message != null ? envelope.error.message : message;
                }
            }
        } catch (Exception ignored) {
            // 保持默认错误码
        }
        if (response.code() == 401) {
            code = ApiException.AUTH_REQUIRED;
        }
        return new ApiException(code, response.code(), message);
    }

    @NonNull
    public static ApiException toNetworkError(IOException e) {
        return new ApiException(ApiException.NETWORK, 0,
                e.getMessage() == null ? "网络不可用" : e.getMessage());
    }

}
