package com.skyanchor.bookkeeping.util;

import android.content.Context;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import com.skyanchor.bookkeeping.R;

/**
 * 分类图表配色。
 *
 * <p>颜色以分类 id 取模分配，保证同一分类在周/月/年不同周期里颜色稳定；
 * 调色板 Token 定义在 {@code colors.xml} 的 {@code chart_1..chart_10}，
 * 应用启动时调用 {@link #init(Context)} 从资源载入，从而支持深色模式。
 * 未初始化时（例如 JVM 单元测试）使用与 Token 一致的内置值兜底。
 */
public final class CategoryColors {

    /** 与 colors.xml 中 chart_1..chart_10 一致的兜底值。 */
    private static final int[] FALLBACK = {
            0xFF2563EB,
            0xFF0EA5E9,
            0xFF14B8A6,
            0xFF16A34A,
            0xFF84CC16,
            0xFFF59E0B,
            0xFFF97316,
            0xFFEF4444,
            0xFFA855F7,
            0xFF64748B,
    };

    private static final int[] COLOR_RES_IDS = {
            R.color.chart_1,
            R.color.chart_2,
            R.color.chart_3,
            R.color.chart_4,
            R.color.chart_5,
            R.color.chart_6,
            R.color.chart_7,
            R.color.chart_8,
            R.color.chart_9,
            R.color.chart_10,
    };

    private static volatile int[] palette = FALLBACK;

    private CategoryColors() {
    }

    /** 从资源载入调色板，需在 {@code Application#onCreate} 中调用。 */
    public static void init(@NonNull Context context) {
        int[] loaded = new int[COLOR_RES_IDS.length];
        for (int i = 0; i < COLOR_RES_IDS.length; i++) {
            loaded[i] = context.getResources().getColor(COLOR_RES_IDS[i], context.getTheme());
        }
        palette = loaded;
    }

    /** 调色板长度。 */
    public static int size() {
        return palette.length;
    }

    /** 按下标取色，越界时循环取模。 */
    @ColorInt
    public static int colorAt(int index) {
        int[] current = palette;
        int safe = index < 0 ? 0 : index % current.length;
        return current[safe];
    }

    /** 按分类 id 取稳定颜色。 */
    @ColorInt
    public static int colorOf(long categoryId) {
        int[] current = palette;
        int index = (int) (Math.abs(categoryId) % current.length);
        return current[index];
    }

    /** 给颜色叠加透明度，用于折线图渐变填充等弱强调场景。 */
    @ColorInt
    public static int withAlpha(@ColorInt int color, int alpha) {
        int safeAlpha = alpha < 0 ? 0 : (alpha > 255 ? 255 : alpha);
        return (color & 0x00FFFFFF) | (safeAlpha << 24);
    }
}
