package com.skyanchor.bookkeeping.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.remote.ApiClient;
import com.skyanchor.bookkeeping.data.remote.ApiDtos;
import com.skyanchor.bookkeeping.data.remote.ApiService;
import com.skyanchor.bookkeeping.util.Callback;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 账本 / 成员 / 邀请 REST 仓库（V3.2 基线第 8、9 章）。
 *
 * <p>成员关系不走同步通道：服务端唯一权威，这里只做薄封装（回调切主线程）。
 * 服务器未配置 / 未登录时回调 {@link ApiClient} 抛出的 {@link com.skyanchor.bookkeeping.data.remote.ApiException}。
 */
public class LedgerRepository {

    private final ApiClient apiClient;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public LedgerRepository(@NonNull ApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public void listLedgers(@Nullable Callback<ApiDtos.LedgerListResponse> callback) {
        run(api -> api.ledgers().execute().body(), callback);
    }

    public void listMembers(@NonNull String ledgerSyncId,
                            @Nullable Callback<ApiDtos.MembersResponse> callback) {
        run(api -> api.ledgerMembers(ledgerSyncId).execute().body(), callback);
    }

    public void invite(@NonNull String ledgerSyncId, @NonNull String email,
                       @NonNull String role, @Nullable Callback<ApiDtos.InvitationItem> callback) {
        run(api -> api.invite(ledgerSyncId,
                new ApiDtos.CreateInvitationRequest(email, role)).execute().body(), callback);
    }

    public void myInvitations(@Nullable Callback<ApiDtos.InvitationsResponse> callback) {
        run(api -> api.myInvitations().execute().body(), callback);
    }

    public void acceptInvitation(@NonNull String invitationId,
                                 @Nullable Callback<ApiDtos.AcceptInvitationResponse> callback) {
        run(api -> api.acceptInvitation(invitationId).execute().body(), callback);
    }

    public void declineInvitation(@NonNull String invitationId,
                                  @Nullable Callback<ApiDtos.SimpleResponse> callback) {
        run(api -> api.declineInvitation(invitationId).execute().body(), callback);
    }

    public void removeMember(@NonNull String ledgerSyncId, long userId,
                             @Nullable Callback<ApiDtos.SimpleResponse> callback) {
        run(api -> api.removeMember(ledgerSyncId, userId).execute().body(), callback);
    }

    public void updateMemberRole(@NonNull String ledgerSyncId, long userId, @NonNull String role,
                                 @Nullable Callback<ApiDtos.SimpleResponse> callback) {
        run(api -> api.updateMemberRole(ledgerSyncId, userId,
                new ApiDtos.UpdateMemberRequest(role)).execute().body(), callback);
    }

    public void restoreLedger(@NonNull String ledgerSyncId,
                              @Nullable Callback<ApiDtos.SimpleResponse> callback) {
        run(api -> api.restoreLedger(ledgerSyncId).execute().body(), callback);
    }

    private interface ApiCall<T> {
        T call(@NonNull ApiService api) throws IOException;
    }

    private <T> void run(@NonNull ApiCall<T> call, @Nullable Callback<T> callback) {
        io.execute(() -> {
            try {
                ApiService api = apiClient.api();
                if (api == null) {
                    postError(callback, new com.skyanchor.bookkeeping.data.remote.ApiException(
                            com.skyanchor.bookkeeping.data.remote.ApiException.NOT_CONFIGURED,
                            0, "请先配置服务器并登录"));
                    return;
                }
                T body = call.call(api);
                post(callback, body);
            } catch (Exception e) {
                postError(callback, e);
            }
        });
    }

    private <T> void post(@Nullable Callback<T> callback, @Nullable T value) {
        if (callback == null || value == null) {
            return;
        }
        main(() -> callback.onResult(value));
    }

    private <T> void postError(@Nullable Callback<T> callback, @NonNull Exception e) {
        if (callback == null) {
            return;
        }
        main(() -> callback.onError(e));
    }

    private void main(@NonNull Runnable task) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
    }
}
