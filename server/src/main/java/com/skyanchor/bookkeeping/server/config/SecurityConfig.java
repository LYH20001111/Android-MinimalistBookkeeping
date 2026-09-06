package com.skyanchor.bookkeeping.server.config;

import com.skyanchor.bookkeeping.server.auth.JwtAuthFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全基线（基线第 30 章）：无状态 JWT、密码 BCrypt、公开路径白名单、
 * 其余接口一律要求已认证；数据库不经过本服务暴露。
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({AppProperties.class, BackupProperties.class})
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/verify-email").permitAll()
                        // 服务器健康检查（V3.1 目标 A）：只含运行状态与容量水位，
                        // 无用户数据，供客户端测试连接 / 管理页 / 人工排障使用
                        .requestMatchers("/api/v1/server/health").permitAll()
                        // 内置极简 Web 管理页（静态资源），数据操作仍走需要鉴权的管理 API
                        .requestMatchers("/admin", "/admin/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                // 未认证（含 Token 过期）必须回 401 + JSON：客户端 OkHttp Authenticator
                // 以 401 为触发条件走 Refresh Token 续期，403/500 会让自动刷新失效
                .exceptionHandling(handling -> handling.authenticationEntryPoint(
                        (request, response, ex) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(
                                    "{\"error\":{\"code\":\"AUTH_REQUIRED\","
                                            + "\"message\":\"登录状态已过期，正在自动续期或需要重新登录\"}}");
                        }))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
