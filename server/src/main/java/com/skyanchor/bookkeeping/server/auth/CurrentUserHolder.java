package com.skyanchor.bookkeeping.server.auth;

/** ThreadLocal 认证上下文，由 JwtAuthFilter 写入、请求结束清理。 */
public final class CurrentUserHolder {

    private static final ThreadLocal<AuthUser> HOLDER = new ThreadLocal<>();

    private CurrentUserHolder() {
    }

    public static AuthUser get() {
        AuthUser user = HOLDER.get();
        if (user == null) {
            throw new IllegalStateException("No authenticated user in context");
        }
        return user;
    }

    public static void set(AuthUser user) {
        HOLDER.set(user);
    }

    public static void clear() {
        HOLDER.remove();
    }
}
