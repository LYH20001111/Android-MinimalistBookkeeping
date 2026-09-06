package com.skyanchor.bookkeeping.server.backup;

import com.skyanchor.bookkeeping.server.auth.AuthUser;
import com.skyanchor.bookkeeping.server.auth.repo.UserRepository;
import com.skyanchor.bookkeeping.server.common.ApiException;
import com.skyanchor.bookkeeping.server.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 管理员判定（V3.1 决策 1：备份 / 恢复由服务器管理员操作）。
 *
 * <p>配置了 {@code app.admin-emails} 时以配置为准（忽略大小写）；
 * 未配置时默认“最早注册的有效账号”是管理员，保证家庭服务器开箱即用。
 */
@Component
public class AdminGuard {

    private final AppProperties appProperties;
    private final UserRepository userRepository;

    public AdminGuard(AppProperties appProperties, UserRepository userRepository) {
        this.appProperties = appProperties;
        this.userRepository = userRepository;
    }

    /** 校验当前登录用户是否管理员；不是则抛 403 NOT_ADMIN。 */
    public void requireAdmin(AuthUser user) {
        if (!isAdmin(user)) {
            throw ApiException.forbidden("NOT_ADMIN",
                    "该操作仅限服务器管理员使用");
        }
    }

    public boolean isAdmin(AuthUser user) {
        var configured = appProperties.getAdminEmails();
        if (configured != null && !configured.isEmpty()) {
            String email = user.email() == null ? "" : user.email().toLowerCase(Locale.ROOT);
            return configured.stream()
                    .filter(item -> item != null && !item.isBlank())
                    .map(item -> item.trim().toLowerCase(Locale.ROOT))
                    .anyMatch(email::equals);
        }
        return userRepository.findFirstByDeletedAtIsNullOrderByIdAsc()
                .map(first -> first.getId() == user.userId())
                .orElse(false);
    }
}
