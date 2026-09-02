package com.skyanchor.bookkeeping.ui.category;

import androidx.annotation.NonNull;

/**
 * 分类编辑弹窗里的预设 emoji 图标（V1 基线第 6 章：icon 使用 emoji 字符串）。
 *
 * <p>与 {@code DefaultData} 一样用 Unicode 转义书写，避免源文件编码差异导致图标丢失。
 * 数量为 6 的整数倍，正好铺满 {@code emoji_grid_span} 列。
 */
public final class EmojiPresets {

    private static final String[] PRESETS = {
            // 餐饮
            "\uD83C\uDF5A", "\uD83C\uDF5C", "\u2615", "\uD83C\uDF70", "\uD83C\uDF4E", "\uD83C\uDF7A",
            // 交通
            "\uD83D\uDE87", "\uD83D\uDE8C", "\uD83D\uDE97", "\u2708\uFE0F", "\uD83D\uDEB2", "\u26FD",
            // 购物与居住
            "\uD83D\uDED2", "\uD83D\uDC55", "\uD83D\uDC5F", "\uD83D\uDC84", "\uD83C\uDFE0", "\uD83D\uDCA1",
            // 数码与娱乐
            "\uD83D\uDCF1", "\uD83D\uDCBB", "\uD83C\uDFAE", "\uD83C\uDFAC", "\uD83C\uDFB5", "\uD83C\uDFC0",
            // 医疗与教育
            "\uD83D\uDC8A", "\uD83C\uDFE5", "\uD83D\uDCDA", "\u270F\uFE0F", "\uD83C\uDF93", "\uD83D\uDC36",
            // 收入与其他
            "\uD83C\uDF81", "\uD83D\uDCBC", "\uD83D\uDCC8", "\uD83D\uDCB0", "\uD83D\uDCB5", "\uD83E\uDDE7",
            // 补充
            "\uD83C\uDFE6", "\uD83D\uDC31", "\uD83C\uDF38", "\u2B50", "\uD83D\uDCA7", "\u2753",
    };

    private EmojiPresets() {
    }

    /** 全部预设图标，调用方不得修改返回内容。 */
    @NonNull
    public static String[] all() {
        return PRESETS.clone();
    }

    /** 新增分类时的默认图标。 */
    @NonNull
    public static String first() {
        return PRESETS[0];
    }
}
