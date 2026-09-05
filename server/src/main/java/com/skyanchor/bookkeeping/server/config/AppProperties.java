package com.skyanchor.bookkeeping.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用配置。jwt-secret 生产环境必须通过环境变量注入；
 * mail-dev-log-only=true 时验证链接只打日志（家庭开发环境）。
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String jwtSecret;
    private long jwtTtlMinutes = 30;
    private long refreshTtlDays = 180;
    private long verifyTtlHours = 24;
    private String baseUrl = "http://localhost:8080";
    private String mailFrom = "no-reply@localhost";
    private boolean mailDevLogOnly = true;

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public long getJwtTtlMinutes() {
        return jwtTtlMinutes;
    }

    public void setJwtTtlMinutes(long jwtTtlMinutes) {
        this.jwtTtlMinutes = jwtTtlMinutes;
    }

    public long getRefreshTtlDays() {
        return refreshTtlDays;
    }

    public void setRefreshTtlDays(long refreshTtlDays) {
        this.refreshTtlDays = refreshTtlDays;
    }

    public long getVerifyTtlHours() {
        return verifyTtlHours;
    }

    public void setVerifyTtlHours(long verifyTtlHours) {
        this.verifyTtlHours = verifyTtlHours;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getMailFrom() {
        return mailFrom;
    }

    public void setMailFrom(String mailFrom) {
        this.mailFrom = mailFrom;
    }

    public boolean isMailDevLogOnly() {
        return mailDevLogOnly;
    }

    public void setMailDevLogOnly(boolean mailDevLogOnly) {
        this.mailDevLogOnly = mailDevLogOnly;
    }
}
