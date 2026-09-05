package com.skyanchor.bookkeeping.server.auth;

import com.skyanchor.bookkeeping.server.common.ApiException;
import com.skyanchor.bookkeeping.server.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * JWT Access Token 签发与校验（短期，默认 30 分钟）。
 * Claims：sub=userId，email，deviceId（客户端设备 UUID），deviceRowId（服务端设备行 id）。
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(AppProperties properties) {
        byte[] secret = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("app.jwt-secret 至少 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.ttl = Duration.ofMinutes(properties.getJwtTtlMinutes());
    }

    public String issue(long userId, String email, String deviceId, long deviceRowId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("deviceId", deviceId)
                .claim("deviceRowId", deviceRowId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(key)
                .compact();
    }

    /** 校验并解析；失败抛 AUTH_REQUIRED（客户端据此走 refresh 流程）。 */
    public AuthUser parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return new AuthUser(
                    Long.parseLong(claims.getSubject()),
                    claims.get("email", String.class),
                    claims.get("deviceId", String.class),
                    claims.get("deviceRowId", Long.class));
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("登录状态已过期");
        }
    }
}
