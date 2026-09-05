package com.skyanchor.bookkeeping.data.remote;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

/**
 * 认证凭据本地存储：access/refresh token、设备身份、登录账号。
 *
 * <p>安全基线：Token 绝不进任何日志；refresh token 泄露即等于账号泄露，
 * 因此这里不提供 toString / dump 途径。V3 使用应用私有 SharedPreferences，
 * 硬件级加密存储列为后续增强。
 */
public class TokenStore {

    private static final String PREFS = "sync_auth";
    private static final String KEY_ACCESS = "access_token";
    private static final String KEY_REFRESH = "refresh_token";
    private static final String KEY_EMAIL = "account_email";
    private static final String KEY_EMAIL_VERIFIED = "email_verified";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_ROW_ID = "device_row_id";

    private final SharedPreferences prefs;

    public TokenStore(@NonNull Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Nullable
    public String getAccessToken() {
        return prefs.getString(KEY_ACCESS, null);
    }

    @Nullable
    public String getRefreshToken() {
        return prefs.getString(KEY_REFRESH, null);
    }

    @Nullable
    public String getAccountEmail() {
        return prefs.getString(KEY_EMAIL, null);
    }

    public boolean isEmailVerified() {
        return prefs.getBoolean(KEY_EMAIL_VERIFIED, false);
    }

    public long getDeviceRowId() {
        return prefs.getLong(KEY_DEVICE_ROW_ID, -1);
    }

    /** 客户端设备 UUID：首次使用时生成并永久保留（服务端设备身份，基线第 19 章）。 */
    @NonNull
    public synchronized String getOrCreateDeviceId() {
        String existing = prefs.getString(KEY_DEVICE_ID, null);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        String deviceId = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
        return deviceId;
    }

    public boolean isLoggedIn() {
        return prefs.getString(KEY_REFRESH, null) != null;
    }

    /** 登录成功 / refresh 旋转成功时更新整套凭据。 */
    public void updateTokens(@Nullable String accessToken, @Nullable String refreshToken,
                             @Nullable String email, boolean emailVerified, long deviceRowId) {
        SharedPreferences.Editor editor = prefs.edit();
        if (accessToken != null) {
            editor.putString(KEY_ACCESS, accessToken);
        }
        if (refreshToken != null) {
            editor.putString(KEY_REFRESH, refreshToken);
        }
        if (email != null) {
            editor.putString(KEY_EMAIL, email);
        }
        editor.putBoolean(KEY_EMAIL_VERIFIED, emailVerified);
        if (deviceRowId >= 0) {
            editor.putLong(KEY_DEVICE_ROW_ID, deviceRowId);
        }
        editor.apply();
    }

    /** 退出登录 / 凭据失效：清空全部凭据，保留 deviceId（同一台设备重登可复用设备行）。 */
    public void clearCredentials() {
        prefs.edit()
                .remove(KEY_ACCESS)
                .remove(KEY_REFRESH)
                .remove(KEY_EMAIL)
                .remove(KEY_EMAIL_VERIFIED)
                .remove(KEY_DEVICE_ROW_ID)
                .apply();
    }
}
