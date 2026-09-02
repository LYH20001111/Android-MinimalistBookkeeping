package com.skyanchor.bookkeeping.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import com.skyanchor.bookkeeping.data.entity.UserSettingsEntity;

/**
 * 主题的本地镜像缓存。
 *
 * <p>Room 里的 {@code user_settings.theme} 是唯一事实来源，但 Application 启动时不允许在主线程
 * 查库。这里用 SharedPreferences 缓存最后一次生效的主题，让 {@code setDefaultNightMode}
 * 能在第一个 Activity 创建之前同步完成，避免冷启动时出现明暗闪烁。
 */
public final class ThemeStore {

    private static final String PREFS_NAME = "bookkeeping_settings";
    private static final String KEY_THEME = "theme";

    private ThemeStore() {
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 读取缓存的主题，缺省为浅色。 */
    @NonNull
    public static String get(@NonNull Context context) {
        return prefs(context).getString(KEY_THEME, UserSettingsEntity.THEME_LIGHT);
    }

    /** 写入主题缓存，与 Room 中的值保持一致。 */
    public static void put(@NonNull Context context, @NonNull String theme) {
        prefs(context).edit().putString(KEY_THEME, theme).apply();
    }

    /** 主题字符串到 {@code AppCompatDelegate} night mode 的映射。 */
    public static int nightMode(@NonNull String theme) {
        if (UserSettingsEntity.THEME_SYSTEM.equals(theme)) {
            return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        return AppCompatDelegate.MODE_NIGHT_NO;
    }

    /** 按缓存值立即应用夜间模式。 */
    public static void apply(@NonNull Context context) {
        AppCompatDelegate.setDefaultNightMode(nightMode(get(context)));
    }
}
