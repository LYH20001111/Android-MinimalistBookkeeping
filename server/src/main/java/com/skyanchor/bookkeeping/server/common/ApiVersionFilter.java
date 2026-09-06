package com.skyanchor.bookkeeping.server.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * API / Sync Protocol 版本协商（基线第 40、41 章：四类版本号分开管理）。
 * 客户端必须携带 X-Api-Version 与 X-Sync-Protocol-Version；不匹配返回 400，
 * 避免旧客户端与新协议之间产生静默的数据错乱。
 * 浏览器打开的邮箱验证链接不携带版本头，予以豁免。
 */
@Component
public class ApiVersionFilter extends OncePerRequestFilter {

    public static final int API_VERSION = 1;
    public static final int SYNC_PROTOCOL_VERSION = 1;

    private static final String PREFIX = "/api/v1/";
    private static final String BROWSER_VERIFY_PATH = "/api/v1/auth/verify-email";
    /** 健康检查面向浏览器 / curl / 管理页，不强制同步协议版本头（V3.1 目标 A）。 */
    private static final String HEALTH_PATH = "/api/v1/server/health";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.startsWith(PREFIX)
                || path.startsWith(BROWSER_VERIFY_PATH)
                || path.equals(HEALTH_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String api = request.getHeader("X-Api-Version");
        String sync = request.getHeader("X-Sync-Protocol-Version");
        if (!String.valueOf(API_VERSION).equals(api)
                || !String.valueOf(SYNC_PROTOCOL_VERSION).equals(sync)) {
            response.setStatus(400);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Map.of("error", Map.of(
                    "code", "VERSION_MISMATCH",
                    "message", "客户端与服务端版本不匹配，请升级 App 或服务器")));
            return;
        }
        chain.doFilter(request, response);
    }
}
