package com.skyanchor.bookkeeping.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 趋势图 X 轴标签选择（V2.1 Phase 2，基线第 7–8 章）。
 *
 * <p>核心原则：数据点与横坐标文字标签解耦——数据点永远完整，只对标签做降采样。
 * 本类是与 Android 无关的纯函数，供 JVM 单元测试直接锁定验收契约：
 * <ul>
 *   <li>首标签与末标签强制显示（用户必须能读到时间范围的起止）；</li>
 *   <li>相邻标签之间像素间距不足 {@code minGapPx} 即判定拥挤，优先减少标签而不是缩小字体；</li>
 *   <li>首选集合放得下就用首选集合（如月视图 1/5/10/15/20/25 + 末日），
 *       放不下退回贪心降采样（近似 1/7/14/21/28 + 末日）；</li>
 *   <li>首标签按绘图区左缘左对齐、末标签按右缘右对齐，杜绝两端裁切。</li>
 * </ul>
 */
public final class ChartAxisLabels {

    private ChartAxisLabels() {
    }

    /**
     * 在 {@code availablePx} 宽的绘图区上，为 {@code count} 个均匀分布的数据点挑选标签下标。
     *
     * <p>数据点位置约定与 {@code LineChartView.xAt} 一致：点 {@code i} 的横坐标为
     * {@code availablePx * i / (count - 1)}。标签对齐约定：下标 0 左对齐于绘图区左缘，
     * 下标 {@code count - 1} 右对齐于右缘，其余居中于数据点。
     *
     * @param count       数据点数量
     * @param labelWidths 每个标签的测量像素宽度（长度可短于 count，缺失视为 0；null 同样视为全 0）
     * @param availablePx 绘图区宽度（已含左右安全边距）
     * @param minGapPx    相邻标签之间的最小空隙，小于该值即判定拥挤
     * @param preferred   首选下标集合（不必有序、可越界，会被过滤）
     * @return 升序标签下标，恒含 0 与 count-1；count&lt;=0 时为空数组
     */
    public static int[] select(int count, @Nullable float[] labelWidths, float availablePx,
                               float minGapPx, @NonNull int[] preferred) {
        if (count <= 0 || availablePx <= 0f) {
            return new int[0];
        }
        if (count == 1) {
            return new int[]{0};
        }
        // 首选集合：过滤越界去重并强制并入首末；相邻间距全部达标才采用
        int[] candidate = sanitize(preferred, count);
        if (fits(candidate, count, labelWidths, availablePx, minGapPx)) {
            return candidate;
        }
        return greedy(count, labelWidths, availablePx, minGapPx);
    }

    // ------------------------------------------------------------------
    // 内部：几何约定与两种选择策略
    // ------------------------------------------------------------------

    /** 标签 index 的可视左缘（首标签左对齐、末标签右对齐、其余居中）。 */
    private static float leftEdge(int index, int pointCount, @Nullable float[] labelWidths,
                                  float availablePx) {
        float width = widthOf(index, labelWidths);
        if (index == 0) {
            return 0f;
        }
        if (index == pointCount - 1) {
            return availablePx - width;
        }
        return xAt(index, pointCount, availablePx) - width / 2f;
    }

    /** 标签 index 的可视右缘。 */
    private static float rightEdge(int index, int pointCount, @Nullable float[] labelWidths,
                                   float availablePx) {
        float width = widthOf(index, labelWidths);
        if (index == 0) {
            return width;
        }
        if (index == pointCount - 1) {
            return availablePx;
        }
        return xAt(index, pointCount, availablePx) + width / 2f;
    }

    private static float xAt(int index, int pointCount, float availablePx) {
        return availablePx * index / (pointCount - 1);
    }

    private static float widthOf(int index, @Nullable float[] labelWidths) {
        if (labelWidths == null || index < 0 || index >= labelWidths.length) {
            return 0f;
        }
        float width = labelWidths[index];
        return width > 0f ? width : 0f;
    }

    /** 过滤越界、去重、补入首末下标，返回升序数组（至少含 0 与 count-1）。 */
    private static int[] sanitize(@NonNull int[] preferred, int count) {
        boolean[] seen = new boolean[count];
        seen[0] = true;
        seen[count - 1] = true;
        for (int index : preferred) {
            if (index >= 0 && index < count) {
                seen[index] = true;
            }
        }
        int size = 0;
        for (boolean flag : seen) {
            if (flag) {
                size++;
            }
        }
        int[] result = new int[size];
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            if (seen[i]) {
                result[cursor++] = i;
            }
        }
        return result;
    }

    /** 首末强制显示 + 相邻间距达标 + 中间标签不越出绘图区。 */
    private static boolean fits(@NonNull int[] indices, int pointCount,
                                @Nullable float[] labelWidths, float availablePx,
                                float minGapPx) {
        if (indices.length > 2) {
            // 中间标签整体必须落在绘图区内（首末按对齐规则天然贴边）
            for (int k = 1; k < indices.length - 1; k++) {
                int index = indices[k];
                if (leftEdge(index, pointCount, labelWidths, availablePx) < -0.01f
                        || rightEdge(index, pointCount, labelWidths, availablePx)
                        > availablePx + 0.01f) {
                    return false;
                }
            }
        }
        for (int k = 1; k < indices.length; k++) {
            float gap = leftEdge(indices[k], pointCount, labelWidths, availablePx)
                    - rightEdge(indices[k - 1], pointCount, labelWidths, availablePx);
            if (gap < minGapPx) {
                return false;
            }
        }
        return true;
    }

    /**
     * 贪心降采样：首标签固定，从左到右能放就放，末标签强制收尾。
     * 每个候选必须同时满足「与上一已选标签不挤」和「与末标签不挤」两个条件，
     * 保证末标签被预留的空间不会被中间标签吃掉。
     */
    private static int[] greedy(int count, @Nullable float[] labelWidths, float availablePx,
                                float minGapPx) {
        int last = count - 1;
        IntList indices = new IntList();
        indices.add(0);
        for (int i = 1; i < last; i++) {
            int previous = indices.get(indices.size() - 1);
            boolean fitsAfterPrevious =
                    leftEdge(i, count, labelWidths, availablePx)
                            - rightEdge(previous, count, labelWidths, availablePx) >= minGapPx;
            boolean fitsBeforeLast =
                    leftEdge(last, count, labelWidths, availablePx)
                            - rightEdge(i, count, labelWidths, availablePx) >= minGapPx;
            if (fitsAfterPrevious && fitsBeforeLast) {
                indices.add(i);
            }
        }
        indices.add(last);
        return indices.toArray();
    }

    /** 避免装箱的小型 int 列表（无 Android 依赖）。 */
    private static final class IntList {

        private int[] values = new int[16];
        private int size;

        void add(int value) {
            if (size == values.length) {
                int[] next = new int[values.length * 2];
                System.arraycopy(values, 0, next, 0, values.length);
                values = next;
            }
            values[size++] = value;
        }

        int get(int index) {
            return values[index];
        }

        int size() {
            return size;
        }

        int[] toArray() {
            int[] result = new int[size];
            System.arraycopy(values, 0, result, 0, size);
            return result;
        }
    }
}
