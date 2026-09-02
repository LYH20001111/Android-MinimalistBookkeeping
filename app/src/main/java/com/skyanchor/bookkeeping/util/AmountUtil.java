package com.skyanchor.bookkeeping.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额工具。
 *
 * <p>V1 基线第 11、12 章要求：金额一律以 long 保存「分」，展示层再格式化为元，
 * 全程不使用 double/float 参与金额计算，避免浮点误差。
 */
public final class AmountUtil {

    /** 人民币符号。 */
    public static final String SYMBOL = "\u00A5";

    /** 解析失败时返回的哨兵值。 */
    public static final long INVALID = -1L;

    /** 允许的最大金额：¥999,999,999.99。 */
    public static final long MAX_CENTS = 99_999_999_999L;

    private static final BigDecimal MAX_CENTS_DECIMAL = BigDecimal.valueOf(MAX_CENTS);

    private AmountUtil() {
    }

    /**
     * 格式化为「¥1,280.00」，负数为「-¥1,280.00」。
     */
    @NonNull
    public static String format(long cents) {
        StringBuilder sb = new StringBuilder();
        if (cents < 0) {
            sb.append('-');
        }
        sb.append(SYMBOL);
        appendAmount(sb, cents);
        return sb.toString();
    }

    /**
     * 只格式化数字部分「1,280.00」，不含货币符号与正负号。
     */
    @NonNull
    public static String formatPlain(long cents) {
        StringBuilder sb = new StringBuilder();
        appendAmount(sb, cents);
        return sb.toString();
    }

    /**
     * 按交易类型加符号：支出「-¥35.00」，收入「+¥35.00」。
     *
     * @param income true 表示收入，false 表示支出
     */
    @NonNull
    public static String formatSigned(long cents, boolean income) {
        StringBuilder sb = new StringBuilder();
        sb.append(income ? '+' : '-');
        sb.append(SYMBOL);
        appendAmount(sb, cents);
        return sb.toString();
    }

    private static void appendAmount(StringBuilder sb, long cents) {
        long abs = Math.abs(cents);
        long yuan = abs / 100L;
        long fen = abs % 100L;
        appendGrouped(sb, yuan);
        sb.append('.');
        if (fen < 10L) {
            sb.append('0');
        }
        sb.append(fen);
    }

    private static void appendGrouped(StringBuilder sb, long value) {
        String digits = Long.toString(value);
        int length = digits.length();
        for (int i = 0; i < length; i++) {
            if (i > 0 && (length - i) % 3 == 0) {
                sb.append(',');
            }
            sb.append(digits.charAt(i));
        }
    }

    /**
     * 把输入框文本解析为「分」。使用 {@link BigDecimal} 保证精确，超过 2 位小数按四舍五入。
     *
     * @return 对应的分值；文本为空、非法、为负数或超出上限时返回 {@link #INVALID}
     */
    public static long parseToCents(@Nullable String text) {
        if (text == null) {
            return INVALID;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return INVALID;
        }
        try {
            BigDecimal value = new BigDecimal(trimmed);
            if (value.signum() < 0) {
                return INVALID;
            }
            BigDecimal cents = value.movePointRight(2).setScale(0, RoundingMode.HALF_UP);
            if (cents.compareTo(MAX_CENTS_DECIMAL) > 0) {
                return INVALID;
            }
            return cents.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return INVALID;
        }
    }

    /**
     * 把「分」转成输入框文本「35.80」，不带货币符号与千分位。
     */
    @NonNull
    public static String toInputText(long cents) {
        long abs = Math.abs(cents);
        long fen = abs % 100L;
        StringBuilder sb = new StringBuilder();
        sb.append(abs / 100L).append('.');
        if (fen < 10L) {
            sb.append('0');
        }
        sb.append(fen);
        return sb.toString();
    }

    /**
     * 图表坐标轴用的紧凑格式：¥980、¥1.2k、¥3.6M。
     */
    @NonNull
    public static String abbreviate(long cents) {
        long abs = Math.abs(cents);
        long yuan = abs / 100L;
        String sign = cents < 0 ? "-" : "";
        if (yuan < 1_000L) {
            return sign + SYMBOL + yuan;
        }
        if (yuan < 1_000_000L) {
            return sign + SYMBOL + oneDecimal(yuan, 1_000L) + "k";
        }
        return sign + SYMBOL + oneDecimal(yuan, 1_000_000L) + "M";
    }

    /** 以整数运算保留一位小数，避免出现浮点误差。 */
    @NonNull
    private static String oneDecimal(long value, long unit) {
        long scaled = value * 10L / unit;
        long whole = scaled / 10L;
        long fraction = scaled % 10L;
        if (fraction == 0L) {
            return Long.toString(whole);
        }
        return whole + "." + fraction;
    }
}
