package com.skyanchor.bookkeeping.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * 「清空所有本地数据」管理员验证的失败计数与锁定状态。
 *
 * <p>持久化到 SharedPreferences：返回我的页面、杀掉应用重进，锁定都继续生效，
 * 必须等满三分钟才能再次尝试；只有系统时间被改动才能绕过，属可接受的防护强度。
 */
public final class AdminVerifyStore {

    private static final String PREFS_NAME = "admin_verify";
    private static final String KEY_FAILED_ATTEMPTS = "failed_attempts";
    private static final String KEY_LOCK_UNTIL = "lock_until";

    private AdminVerifyStore() {
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 当前累计的连续失败次数。 */
    public static int failedAttempts(@NonNull Context context) {
        return prefs(context).getInt(KEY_FAILED_ATTEMPTS, 0);
    }

    /** 记一次失败，返回累计次数。 */
    public static int recordFailure(@NonNull Context context) {
        SharedPreferences p = prefs(context);
        int attempts = p.getInt(KEY_FAILED_ATTEMPTS, 0) + 1;
        p.edit().putInt(KEY_FAILED_ATTEMPTS, attempts).apply();
        return attempts;
    }

    /** 是否处于锁定中。按墙钟判断，跨进程重启仍然成立。 */
    public static boolean isLocked(@NonNull Context context) {
        return System.currentTimeMillis() < lockUntil(context);
    }

    /** 锁定截止时间的墙钟毫秒值，未锁定过返回 0。 */
    public static long lockUntil(@NonNull Context context) {
        return prefs(context).getLong(KEY_LOCK_UNTIL, 0L);
    }

    /** 进入锁定：从现在起锁 lockMillis，并清零失败计数，到期后下一轮从零开始。 */
    public static void lock(@NonNull Context context, long lockMillis) {
        prefs(context).edit()
                .putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + lockMillis)
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .apply();
    }

    /** 清空失败计数与锁定状态：验证通过或锁定到期后调用。 */
    public static void reset(@NonNull Context context) {
        prefs(context).edit().clear().apply();
    }
}
