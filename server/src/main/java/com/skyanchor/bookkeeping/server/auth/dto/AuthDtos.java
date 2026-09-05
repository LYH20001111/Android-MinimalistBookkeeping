package com.skyanchor.bookkeeping.server.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 认证接口请求/响应体。身份一律取自 JWT，不含客户端可信 userId。 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 64, message = "密码长度需在 8-64 位之间") String password) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password,
            DeviceInfo device) {
    }

    /** 客户端设备自述：deviceId 为客户端持久化 UUID，名称/平台/版本仅用于展示。 */
    public record DeviceInfo(String deviceId, String deviceName, String platform, String appVersion) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record DeleteAccountRequest(@NotBlank String password) {
    }

    public record AuthResponse(String accessToken, String refreshToken, String email,
                               boolean emailVerified, DeviceDto device) {
    }

    public record RefreshResponse(String accessToken, String refreshToken, long expiresAt) {
    }

    public record DeviceDto(long id, String deviceId, String deviceName, String platform,
                            String appVersion, long lastSeenAt, long createdAt,
                            boolean revoked, boolean current) {
    }

    public record SimpleResponse(boolean ok, String message) {
    }
}
