package com.skyanchor.bookkeeping.util;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 窗口 Insets 处理。
 *
 * <p>targetSdk 35 及以上强制 edge-to-edge，内容会绘制到状态栏与导航栏下方，
 * 主题里的 {@code statusBarColor} 不再生效。所有页面统一通过这里把系统栏高度
 * 转成 padding，避免出现文字被状态栏遮挡、底部导航被手势条压住的问题。
 */
public final class InsetsUtil {

    private InsetsUtil() {
    }

    /** 顶部 + 左右内边距，用于页面根布局（底部由具体控件自行处理）。 */
    public static void applyTopAndHorizontalPadding(@NonNull View view) {
        listen(view, true, false, true, false);
    }

    /** 仅底部内边距，用于贴底的 BottomNavigationView / 操作栏。 */
    public static void applyBottomPadding(@NonNull View view) {
        listen(view, false, true, false, false);
    }

    /** 四周系统栏内边距，用于内容不贴底的整页容器。 */
    public static void applySystemBarsPadding(@NonNull View view) {
        listen(view, true, true, true, false);
    }

    /**
     * 底部内边距跟随输入法，用于带输入框的编辑页：
     * 键盘弹出时内容整体上移，收起时回落到导航栏高度。
     */
    public static void applyImeBottomPadding(@NonNull View view) {
        final int basePaddingBottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (target, insets) -> {
            int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            target.setPadding(target.getPaddingLeft(), target.getPaddingTop(),
                    target.getPaddingRight(), basePaddingBottom + Math.max(ime, bars));
            return insets;
        });
    }

    private static void listen(@NonNull View view, boolean top, boolean bottom,
                               boolean horizontal, boolean ime) {
        final int baseLeft = view.getPaddingLeft();
        final int baseTop = view.getPaddingTop();
        final int baseRight = view.getPaddingRight();
        final int baseBottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (target, insets) -> {
            int bars = WindowInsetsCompat.Type.systemBars();
            if (ime) {
                bars |= WindowInsetsCompat.Type.ime();
            }
            Insets size = insets.getInsets(bars);
            target.setPadding(
                    horizontal ? baseLeft + size.left : baseLeft,
                    top ? baseTop + size.top : baseTop,
                    horizontal ? baseRight + size.right : baseRight,
                    bottom ? baseBottom + size.bottom : baseBottom);
            return insets;
        });
    }

    /**
     * 同步系统栏图标明暗。深色模式下必须用浅色图标，否则状态栏时间与电量看不清。
     * 主题的 {@code windowLightStatusBar} 在 edge-to-edge 下不再可靠，这里显式设置一次。
     */
    public static void syncSystemBarAppearance(@NonNull Activity activity) {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
        boolean lightBackground = !isNightMode(activity);
        controller.setAppearanceLightStatusBars(lightBackground);
        controller.setAppearanceLightNavigationBars(lightBackground);
    }

    /** 当前是否处于深色模式（含「跟随系统」被解析为深色的情况）。 */
    public static boolean isNightMode(@NonNull Activity activity) {
        int mode = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }
}
