package com.skyanchor.bookkeeping.data.remote;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 服务器地址配置（开发计划备注 10）：由用户在同步中心填写，
 * 如 {@code http://192.168.1.10:8080} 或 {@code https://sync.example.com}。
 */
public class ServerConfigStore {

    private static final String PREFS = "sync_server";
    private static final String KEY_BASE_URL = "base_url";

    private final SharedPreferences prefs;

    public ServerConfigStore(@NonNull Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Nullable
    public String getBaseUrl() {
        String url = prefs.getString(KEY_BASE_URL, null);
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        String trimmed = url.trim();
        if (!trimmed.endsWith("/")) {
            trimmed = trimmed + "/";
        }
        return trimmed;
    }

    public void setBaseUrl(@Nullable String url) {
        if (url == null || url.trim().isEmpty()) {
            prefs.edit().remove(KEY_BASE_URL).apply();
        } else {
            prefs.edit().putString(KEY_BASE_URL, url.trim()).apply();
        }
    }

    public boolean isConfigured() {
        return getBaseUrl() != null;
    }
}
