package com.skyanchor.bookkeeping.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.remote.ApiDtos;
import com.skyanchor.bookkeeping.data.remote.ApiClient;
import com.skyanchor.bookkeeping.data.remote.ApiService;
import com.skyanchor.bookkeeping.util.Callback;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务器信息仓库（V3.1 基线第 39/40 章）：健康检查与冲突历史。
 * 只读展示类接口——客户端不触发服务器备份 / 恢复（V3.1 决策 1：
 * 那是服务器管理员通过 Web 管理页或 REST API 的操作）。
 */
public class ServerRepository {

    private final ApiServiceSupplier apiSupplier;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** 延迟取 ApiService（BaseUrl 可能被重建，不能缓存实例）。 */
    public interface ApiServiceSupplier {
        @Nullable
        ApiService get();
    }

    public ServerRepository(@NonNull ApiServiceSupplier apiSupplier) {
        this.apiSupplier = apiSupplier;
    }

    /**
     * 服务器健康检查（公开端点，基线第 10 章）。失败时回调 null 并带出异常，
     * 文案转译交给 UI 层的 ConnectionErrorMapper。
     */
    public void getHealth(@NonNull Callback<ApiDtos.ServerHealthResponse> callback) {
        ApiService api = apiSupplier.get();
        if (api == null) {
            post(callback, null, null);
            return;
        }
        io.execute(() -> {
            try {
                retrofit2.Response<ApiDtos.ServerHealthResponse> response =
                        api.serverHealth().execute();
                if (response.isSuccessful() && response.body() != null) {
                    post(callback, response.body(), null);
                } else {
                    post(callback, null, ApiClient.toApiError(response));
                }
            } catch (IOException e) {
                post(callback, null, e);
            } catch (Exception e) {
                post(callback, null, e);
            }
        });
    }

    /** 冲突历史（V3.1 基线第 26 章）：最近 N 条冲突审计摘要。 */
    public void getConflicts(int limit, @NonNull Callback<List<ApiDtos.ConflictItem>> callback) {
        ApiService api = apiSupplier.get();
        if (api == null) {
            post(callback, null, null);
            return;
        }
        io.execute(() -> {
            try {
                retrofit2.Response<ApiDtos.ConflictsResponse> response =
                        api.conflicts(limit).execute();
                if (response.isSuccessful() && response.body() != null) {
                    post(callback, response.body().conflicts, null);
                } else {
                    post(callback, null, ApiClient.toApiError(response));
                }
            } catch (IOException e) {
                post(callback, null, e);
            } catch (Exception e) {
                post(callback, null, e);
            }
        });
    }

    private <T> void post(@NonNull Callback<T> callback, @Nullable T value,
                          @Nullable Exception error) {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(() -> {
            if (error != null) {
                callback.onError(error);
            } else {
                callback.onResult(value);
            }
        });
    }
}
