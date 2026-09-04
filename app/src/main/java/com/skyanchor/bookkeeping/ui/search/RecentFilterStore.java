package com.skyanchor.bookkeeping.ui.search;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索筛选「最近使用」记录（V2.1 Phase 1，P1 增强）。
 *
 * <p>按作用域（分类 / 账户）在 SharedPreferences 里保存最近使用的 id，最多
 * {@link #MAX_RECENT} 条、最新在前；只作选择器顶部快捷入口，不改变完整列表排序
 * （基线 5.5）。伪选中（id=0 的「全部」哨兵）不记录。
 *
 * <p>纯本地偏好，不进数据库、不进备份恢复：丢失只影响快捷入口，不影响任何业务数据。
 */
public final class RecentFilterStore {

    private static final String PREF_NAME = "recent_filters";
    private static final String KEY_PREFIX = "recent_ids_";
    private static final int MAX_RECENT = 3;

    /** 分类作用域。 */
    public static final String SCOPE_CATEGORY = "category";
    /** 账户作用域。 */
    public static final String SCOPE_ACCOUNT = "account";

    private RecentFilterStore() {
    }

    /** 记录一次真实选择（id<=0 忽略），最新在前、去重、最多 3 条。 */
    public static void record(@NonNull Context context, @NonNull String scope, long id) {
        if (id <= 0L) {
            return;
        }
        SharedPreferences prefs = prefs(context);
        List<Long> ids = parse(prefs.getString(KEY_PREFIX + scope, ""));
        Long boxed = id;
        ids.remove(boxed);
        ids.add(0, boxed);
        while (ids.size() > MAX_RECENT) {
            ids.remove(ids.size() - 1);
        }
        prefs.edit().putString(KEY_PREFIX + scope, join(ids)).apply();
    }

    /** 读取最近使用 id，最新在前；无记录返回空数组。 */
    @NonNull
    public static long[] recentIds(@NonNull Context context, @NonNull String scope) {
        List<Long> ids = parse(prefs(context).getString(KEY_PREFIX + scope, ""));
        long[] result = new long[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            result[i] = ids.get(i);
        }
        return result;
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    @NonNull
    private static List<Long> parse(@Nullable String raw) {
        List<Long> ids = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return ids;
        }
        for (String part : raw.split("\\|")) {
            try {
                long id = Long.parseLong(part);
                if (id > 0L) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
                // 单段损坏直接丢弃，不影响其余条目
            }
        }
        return ids;
    }

    @NonNull
    private static String join(@NonNull List<Long> ids) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                builder.append('|');
            }
            builder.append(ids.get(i));
        }
        return builder.toString();
    }
}
