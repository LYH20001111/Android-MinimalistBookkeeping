package com.skyanchor.bookkeeping.server.common;

/**
 * 统一业务异常：携带稳定错误码 + HTTP 状态 + 面向客户端的可读消息。
 * 消息均为人类可读文案，不暴露堆栈与内部细节。
 */
public class ApiException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public ApiException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "VALIDATION_ERROR", message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, "AUTH_REQUIRED", message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(403, code, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, "CONFLICT", message);
    }

    public static ApiException tooManyRequests() {
        return new ApiException(429, "RATE_LIMITED", "请求过于频繁，请稍后再试");
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }
}
