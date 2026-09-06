package com.skyanchor.bookkeeping.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.remote.ApiClient;
import com.skyanchor.bookkeeping.data.remote.ApiException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 连接错误文案映射（V3.1 基线第 9 章）：把底层异常转成用户可理解的文案，
 * 禁止把 SocketException / Connection refused / HTTP 500 直接暴露给用户。
 */
public final class ConnectionErrorMapper {

    private ConnectionErrorMapper() {
    }

    /** 服务器不可达的原因清单（基线第 9 章固定文案）。 */
    @NonNull
    public static String unreachableReasons() {
        return "无法连接服务器\n\n可能原因：\n· 服务器未开机或程序未运行\n"
                + "· 服务器地址填错了\n· 手机与电脑不在同一网络\n· 防火墙阻止了连接";
    }

    /** 把任意异常映射为用户可读的一句话摘要（用于 Snackbar / 状态行）。 */
    @NonNull
    public static String summarize(@Nullable Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        if (error instanceof ApiException) {
            ApiException api = (ApiException) error;
            if (api.isNetworkLevel()) {
                return "网络不可用或服务器无法访问";
            }
            return api.getMessage() != null ? api.getMessage() : "服务器返回错误";
        }
        if (error instanceof UnknownHostException) {
            return "服务器地址无法解析，请检查地址是否填对";
        }
        if (error instanceof ConnectException) {
            return "连接被拒绝，服务器可能没有运行";
        }
        if (error instanceof SocketTimeoutException) {
            return "连接超时，请确认网络与服务器状态";
        }
        if (error instanceof java.io.IOException) {
            return "网络连接失败，请检查服务器与网络";
        }
        return error.getMessage() != null ? error.getMessage() : "未知错误";
    }

    /** 未配置服务器地址时的固定提示。 */
    @NonNull
    public static String notConfigured() {
        return "尚未配置服务器地址";
    }

    /** 判断异常是否属于“服务器不可达”一类（用于区分展示原因清单还是具体错误）。 */
    public static boolean isUnreachable(@Nullable Throwable error) {
        if (error == null) {
            return false;
        }
        if (error instanceof ApiException) {
            return ((ApiException) error).isNetworkLevel();
        }
        return error instanceof java.io.IOException;
    }

    /** 判断当前 BaseUrl 是否为 HTTPS（安全连接提示用，基线第 32 章）。 */
    public static boolean isSecure(@Nullable String baseUrl) {
        return baseUrl != null && baseUrl.trim().toLowerCase(java.util.Locale.ROOT)
                .startsWith("https://");
    }

    /** 便捷重载：包装 ApiClient 错误转换结果。 */
    @NonNull
    public static String fromRetrofitError(@Nullable retrofit2.Response<?> response) {
        if (response == null) {
            return unreachableReasons();
        }
        ApiException api = ApiClient.toApiError(response);
        return summarize(api);
    }

    /** 字节数 → 人类可读容量（磁盘状态展示用，基线第 34 章）。 */
    @NonNull
    public static String humanBytes(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        double value = bytes;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        if (unit == 0) {
            return (long) value + " " + units[unit];
        }
        return String.format(java.util.Locale.US, "%.1f %s", value, units[unit]);
    }
}
