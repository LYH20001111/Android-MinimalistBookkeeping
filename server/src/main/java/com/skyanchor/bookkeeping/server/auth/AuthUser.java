package com.skyanchor.bookkeeping.server.auth;

/**
 * 认证上下文：从 JWT 解出，服务层只信任这里的 userId/deviceId，
 * 绝不信任客户端请求体传入的身份字段（基线第 31 章数据隔离原则）。
 */
public record AuthUser(long userId, String email, String deviceId, long deviceRowId) {

    public static AuthUser current() {
        return CurrentUserHolder.get();
    }
}
