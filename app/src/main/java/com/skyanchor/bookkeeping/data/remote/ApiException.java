package com.skyanchor.bookkeeping.data.remote;

/**
 * API 调用失败：携带稳定错误码与 HTTP 状态，由上层转译成人类可读文案（基线第 35 章），
 * 不把 HTTP 细节暴露给用户。
 */
public class ApiException extends Exception {

    public static final String AUTH_REQUIRED = "AUTH_REQUIRED";
    public static final String EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String SERVER_UNAVAILABLE = "SERVER_UNAVAILABLE";
    public static final String NETWORK = "NETWORK";
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";

    public final String code;
    public final int httpStatus;

    public ApiException(String code, int httpStatus, String detail) {
        super(detail);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public boolean isAuthRequired() {
        return AUTH_REQUIRED.equals(code);
    }

    public boolean isEmailNotVerified() {
        return EMAIL_NOT_VERIFIED.equals(code);
    }

    public boolean isNetworkLevel() {
        return NETWORK.equals(code) || SERVER_UNAVAILABLE.equals(code) || NOT_CONFIGURED.equals(code);
    }
}
