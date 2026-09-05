package com.skyanchor.bookkeeping.server.auth;

import com.skyanchor.bookkeeping.server.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮件发送。mail-dev-log-only=true（家庭开发环境默认）时只把验证链接打到日志，
 * 便于无 SMTP 情况下完成全流程；生产/家庭部署配置 SMTP 后自动真实发信。
 * 日志只输出验证链接（一次性、24h 过期），绝不输出密码或登录令牌。
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final AppProperties properties;
    private final JavaMailSender mailSender;

    public MailService(AppProperties properties, JavaMailSender mailSender) {
        this.properties = properties;
        this.mailSender = mailSender;
    }

    public void sendVerificationEmail(String to, String verificationToken) {
        String link = properties.getBaseUrl() + "/api/v1/auth/verify-email?token=" + verificationToken;
        if (properties.isMailDevLogOnly()) {
            log.info("[DEV] 邮箱验证链接（{}，24 小时内有效）：{}", to, link);
            return;
        }
        try {
            var message = mailSender.createMimeMessage();
            var helper = new org.springframework.mail.javamail.MimeMessageHelper(message, "UTF-8");
            helper.setFrom(properties.getMailFrom());
            helper.setTo(to);
            helper.setSubject("极简记账 · 邮箱验证");
            helper.setText("""
                    您正在注册极简记账云同步账号。点击以下链接完成邮箱验证（24 小时内有效，仅可使用一次）：

                    %s

                    如果这不是您本人的操作，请忽略本邮件。""".formatted(link));
            mailSender.send(message);
        } catch (Exception e) {
            log.error("验证邮件发送失败（收件人已脱敏）：{}", e.getMessage());
            throw new IllegalStateException("验证邮件发送失败，请稍后重试", e);
        }
    }
}
