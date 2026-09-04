package com.skyanchor.bookkeeping.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * {@link ChartAxisLabels} 的验收契约（V2.1 基线 28.2）：
 * 数据点与标签解耦、末标签强制显示、间距不挤、两端不裁切。
 */
public class ChartAxisLabelsTest {

    private static final float AVAILABLE = 300f;
    private static final float MIN_GAP = 16f;

    /** 与实现约定一致的标签可视区间（首左对齐 / 末右对齐 / 其余居中）。 */
    private static float[] span(int index, int count, float width, float available) {
        if (index == 0) {
            return new float[]{0f, width};
        }
        if (index == count - 1) {
            return new float[]{available - width, available};
        }
        float x = available * index / (count - 1);
        return new float[]{x - width / 2f, x + width / 2f};
    }

    /** 断言：升序、恒含首末、相邻标签间距 ≥ minGap、中间标签不越出绘图区。 */
    private static void assertValid(int[] indices, int count, float[] widths) {
        assertValid(indices, count, widths, AVAILABLE);
    }

    private static void assertValid(int[] indices, int count, float[] widths, float available) {
        assertTrue("至少要有一个标签", indices.length > 0);
        assertEquals("必须包含首标签", 0, indices[0]);
        assertEquals("必须包含末标签（最后一天强制显示）", count - 1, indices[indices.length - 1]);
        for (int k = 1; k < indices.length; k++) {
            float[] previous = span(indices[k - 1], count, widths[indices[k - 1]], available);
            float[] current = span(indices[k], count, widths[indices[k]], available);
            assertTrue("相邻标签不得拥挤 (" + indices[k - 1] + "→" + indices[k] + ")",
                    current[0] - previous[1] >= MIN_GAP - 0.01f);
        }
        for (int k = 1; k < indices.length - 1; k++) {
            float[] s = span(indices[k], count, widths[indices[k]], available);
            assertTrue("中间标签不得越出绘图区", s[0] >= -0.01f && s[1] <= available + 0.01f);
        }
    }

    private static float[] uniformWidths(int count, float width) {
        float[] widths = new float[count];
        Arrays.fill(widths, width);
        return widths;
    }

    private static int[] allOf(int count) {
        int[] all = new int[count];
        for (int i = 0; i < count; i++) {
            all[i] = i;
        }
        return all;
    }

    @Test
    public void emptyAndSingle() {
        assertArrayEquals(new int[0], ChartAxisLabels.select(0, null, AVAILABLE, MIN_GAP,
                new int[]{0}));
        assertArrayEquals(new int[]{0}, ChartAxisLabels.select(1, null, AVAILABLE, MIN_GAP,
                new int[]{0}));
    }

    @Test
    public void weekShowsAllSevenWhenTheyFit() {
        // 7 个窄标签在 300px 里足够放下：0..6 全显
        int[] indices = ChartAxisLabels.select(7, uniformWidths(7, 20f), AVAILABLE, MIN_GAP,
                allOf(7));
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6}, indices);
    }

    @Test
    public void yearShowsAllTwelveWhenTheyFit() {
        // 12 个月标签（宽 12px）在 420px 里：步距 ≈38；首标签左对齐后
        // 与第 2 个标签的间隙 ≈ 38.18 - 12/2 - 12 = 20.2 ≥ 16 → 全显
        int[] indices = ChartAxisLabels.select(12, uniformWidths(12, 12f), 420f, MIN_GAP,
                allOf(12));
        assertEquals(12, indices.length);
        assertValid(indices, 12, uniformWidths(12, 12f), 420f);
    }

    @Test
    public void yearDownsamplesOnNarrowScreenButKeepsLast() {
        float[] widths = uniformWidths(12, 18f);
        int[] indices = ChartAxisLabels.select(12, widths, AVAILABLE, MIN_GAP, allOf(12));
        assertValid(indices, 12, widths);
        assertTrue("窄屏应降采样", indices.length < 12);
    }

    @Test
    public void month31UsesPreferredWhenTheyFit() {
        // 31 天，标签宽 12px：首选 1/5/10/15/20/25/31 的相邻间隔
        // = 300*5/30 - 12 = 38 ≥ 16 → 首选成立
        int[] indices = ChartAxisLabels.select(31, uniformWidths(31, 12f), AVAILABLE, MIN_GAP,
                new int[]{0, 4, 9, 14, 19, 24});
        assertArrayEquals(new int[]{0, 4, 9, 14, 19, 24, 30}, indices);
    }

    @Test
    public void month31DownsamplesOnNarrowScreenButKeepsFirstAndLast() {
        // 标签宽 40px：首选相邻间隔 50px - 40 = 10 < 16 → 降采样
        float[] widths = uniformWidths(31, 40f);
        int[] indices = ChartAxisLabels.select(31, widths, AVAILABLE, MIN_GAP,
                new int[]{0, 4, 9, 14, 19, 24});
        assertValid(indices, 31, widths);
        assertTrue("降采样后标签应明显变少", indices.length <= 8);
    }

    @Test
    public void month28_29_30_31AllKeepLastLabel() {
        for (int days : new int[]{28, 29, 30, 31}) {
            float[] widths = uniformWidths(days, 14f);
            int[] indices = ChartAxisLabels.select(days, widths, AVAILABLE, MIN_GAP,
                    new int[]{0, 4, 9, 14, 19, 24});
            assertValid(indices, days, widths);
            assertEquals(days - 1, indices[indices.length - 1]);
        }
    }

    @Test
    public void extremeNarrowStillReturnsFirstAndLast() {
        float[] widths = uniformWidths(31, 60f);
        int[] indices = ChartAxisLabels.select(31, widths, AVAILABLE, MIN_GAP,
                new int[]{0, 4, 9, 14, 19, 24});
        assertEquals(0, indices[0]);
        assertEquals(30, indices[indices.length - 1]);
    }

    @Test
    public void preferredOutOfRangeAndDuplicatesAreTolerated() {
        // 首选集合里有效项只有 3，其余越界 / 重复被过滤；首末强制并入 → [0,3,6]
        int[] indices = ChartAxisLabels.select(7, uniformWidths(7, 20f), AVAILABLE, MIN_GAP,
                new int[]{-1, 0, 3, 3, 99});
        assertArrayEquals(new int[]{0, 3, 6}, indices);
    }
}
