package com.skyanchor.bookkeeping.server.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 鉴权过滤器：把 Bearer Token 解析为 {@link AuthUser} 写入 SecurityContext 与
 * ThreadLocal；公开路径直接放行。任何解析失败都不阻断匿名访问公开接口，
 * 受保护接口由 Security 规则兜底返回 401。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final RequestMatcher PUBLIC_PATHS = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/auth/register"),
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/auth/login"),
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/auth/refresh"),
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/auth/verify-email"));

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_PATHS.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                // Token 缺失 / 过期 / 非法：不抛异常（过滤器链不经过全局异常处理器，
                // 抛出会变成 500），只是不设置认证上下文，由 Security 以 401 拒绝——
                // 客户端 OkHttp Authenticator 收到 401 才会走 Refresh Token 自动续期。
                AuthUser user = jwtService.parseQuietly(header.substring(7).trim());
                if (user != null) {
                    CurrentUserHolder.set(user);
                    var authentication = new org.springframework.security.authentication
                            .UsernamePasswordAuthenticationToken(user, null, java.util.List.of());
                    org.springframework.security.core.context.SecurityContextHolder
                            .getContext().setAuthentication(authentication);
                }
            }
            chain.doFilter(request, response);
        } finally {
            CurrentUserHolder.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
