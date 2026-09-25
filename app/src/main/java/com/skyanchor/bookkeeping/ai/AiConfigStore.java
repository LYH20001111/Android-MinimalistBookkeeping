package com.skyanchor.bookkeeping.ai;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * AI 服务配置（V5）：OpenAI 兼容接口的地址 / 密钥 / 模型名，由用户在
 * AI 设置页填写；未配置时智能记账走端侧离线规则识别。
 *
 * <p>持久化方式与 {@code ServerConfigStore} 一致：SharedPreferences 明文存储。
 * 密钥仅存本机，随智能记账请求发往用户自己配置的服务商。
 */
public class AiConfigStore {

    private static final String PREFS = "ai_service";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";
    /** 「记一笔」页右上角智能入口的一次性引导是否已展示（基线第 41 章）。 */
    private static final String KEY_SMART_HINT_SHOWN = "smart_hint_shown";

    private final SharedPreferences prefs;

    public AiConfigStore(@NonNull Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 形如 {@code https://api.deepseek.com/v1}，不含尾斜杠；未配置返回 null。 */
    @Nullable
    public String getBaseUrl() {
        return trimmed(KEY_BASE_URL);
    }

    public void setBaseUrl(@Nullable String url) {
        putTrimmed(KEY_BASE_URL, url);
    }

    @Nullable
    public String getApiKey() {
        return trimmed(KEY_API_KEY);
    }

    public void setApiKey(@Nullable String key) {
        putTrimmed(KEY_API_KEY, key);
    }

    /** 模型名，如 {@code deepseek-chat}。允许为空：部分兼容网关不需要。 */
    @Nullable
    public String getModel() {
        return trimmed(KEY_MODEL);
    }

    public void setModel(@Nullable String model) {
        putTrimmed(KEY_MODEL, model);
    }

    /** 云端 AI 是否可用（地址 + 密钥齐备）。未配置时解析层直接走本地规则。 */
    public boolean isCloudConfigured() {
        return getBaseUrl() != null && getApiKey() != null;
    }

    public boolean isSmartHintShown() {
        return prefs.getBoolean(KEY_SMART_HINT_SHOWN, false);
    }

    public void markSmartHintShown() {
        prefs.edit().putBoolean(KEY_SMART_HINT_SHOWN, true).apply();
    }

    @Nullable
    private String trimmed(String key) {
        String value = prefs.getString(key, null);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private void putTrimmed(@NonNull String key, @Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            prefs.edit().remove(key).apply();
        } else {
            prefs.edit().putString(key, value.trim()).apply();
        }
    }
}
