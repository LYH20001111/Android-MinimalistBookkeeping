package com.skyanchor.bookkeeping.data.model;

/**
 * 分类统计项：金额 + 占比 + 排名（V1 基线第 7.1 节第三层）。
 */
public final class CategoryStat {

    public final long categoryId;

    public final String name;

    public final String icon;

    /** 该分类的金额合计，单位：分。 */
    public final long amount;

    /**
     * 占比，千分比整数：299 表示 29.9%。
     * 由最大余额法分配，保证同一周期内所有分类的 percentX10 之和恰为 1000。
     */
    public final int percentX10;

    /** 图表配色，ARGB。 */
    public final int color;

    public CategoryStat(long categoryId, String name, String icon, long amount, int percentX10,
                        int color) {
        this.categoryId = categoryId;
        this.name = name;
        this.icon = icon;
        this.amount = amount;
        this.percentX10 = percentX10;
        this.color = color;
    }

    /** 格式化为「29.9%」。 */
    public String percentText() {
        int whole = percentX10 / 10;
        int fraction = percentX10 % 10;
        if (fraction == 0) {
            return whole + "%";
        }
        return whole + "." + fraction + "%";
    }

    /** 占进度条比例的浮点值，仅用于绘制宽度，不参与金额计算。 */
    public float ratio() {
        return percentX10 / 1000f;
    }
}
