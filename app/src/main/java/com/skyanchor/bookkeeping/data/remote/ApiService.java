package com.skyanchor.bookkeeping.data.remote;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

/** 服务端 REST 接口定义（基线第 29 章）。UI/业务层不得直接引用本接口。 */
public interface ApiService {

    // ===== Auth =====

    @POST("api/v1/auth/register")
    Call<ApiDtos.SimpleResponse> register(@Body ApiDtos.RegisterRequest request);

    @POST("api/v1/auth/resend-verification")
    Call<ApiDtos.SimpleResponse> resendVerification(@Body ApiDtos.LoginRequest request);

    @POST("api/v1/auth/login")
    Call<ApiDtos.AuthResponse> login(@Body ApiDtos.LoginRequest request);

    @POST("api/v1/auth/refresh")
    Call<ApiDtos.RefreshResponse> refresh(@Body ApiDtos.RefreshRequest request);

    @POST("api/v1/auth/logout")
    Call<ApiDtos.SimpleResponse> logout();

    @POST("api/v1/auth/logout-all")
    Call<ApiDtos.SimpleResponse> logoutAll();

    @GET("api/v1/devices")
    Call<java.util.List<ApiDtos.DeviceDto>> listDevices();

    @POST("api/v1/devices/{id}/revoke")
    Call<ApiDtos.SimpleResponse> revokeDevice(@Path("id") long deviceRowId);

    @DELETE("api/v1/account")
    Call<ApiDtos.SimpleResponse> deleteAccount(@Body ApiDtos.DeleteAccountRequest request);

    // ===== Sync =====

    @POST("api/v1/sync/changes/push")
    Call<ApiDtos.PushResponse> push(@Body ApiDtos.PushRequest request);

    @POST("api/v1/sync/changes/pull")
    Call<ApiDtos.PullResponse> pull(@Body ApiDtos.PullRequest request);

    @GET("api/v1/sync/bootstrap/summary")
    Call<ApiDtos.BootstrapSummaryResponse> bootstrapSummary();

    @GET("api/v1/sync/status")
    Call<ApiDtos.StatusResponse> status();

    /** 冲突历史（V3.1 基线第 26 章）。 */
    @GET("api/v1/sync/conflicts")
    Call<ApiDtos.ConflictsResponse> conflicts(@retrofit2.http.Query("limit") int limit);

    // ===== Server（V3.1） =====

    /** 服务器健康检查：公开端点，供测试连接与状态展示。 */
    @GET("api/v1/server/health")
    Call<ApiDtos.ServerHealthResponse> serverHealth();
}
