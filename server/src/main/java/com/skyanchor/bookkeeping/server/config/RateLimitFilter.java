package com.skyanchor.bookkeeping.server.config;

import com.skyanchor.bookkeeping.server.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证接口 IP 限流（基线第 30 章「基础请求限流 / 防暴力破解」）。
 * 内存滑动窗口：每 IP 在窗口内的认证类请求超过阈值即返回 429。
 * 家庭服务器规模下内存实现足够，不引入外部依赖。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int WINDOW_MS = 60_000;
    private static final int MAX_REQUESTS = 30;
    private static final String PREFIX = "/api/v1/auth/";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ip = clientIp(request);
        long now = System.currentTimeMillis();
        Deque<Long> window = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() >= MAX_REQUESTS) {
                writeTooMany(response);
                return;
            }
            window.addLast(now);
        }
        chain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeTooMany(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                Map.of("error", Map.of("code", "RATE_LIMITED", "message", "请求过于频繁，请稍后再试")));
    }
}
