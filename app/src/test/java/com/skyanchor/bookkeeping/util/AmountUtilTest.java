package com.skyanchor.bookkeeping.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 金额工具单元测试（V1 基线第 11、16 章「金额展示与输入无浮点误差」）。
 *
 * <p>金额一律以 long「分」参与计算，这里同时验证格式化、解析与紧凑格式三条路径。
 */
public class AmountUtilTest {

    // ------------------------------------------------------------------
    // 格式化
    // ------------------------------------------------------------------

    @Test
    public void format_rendersYuanWithTwoDecimals() {
        assertEquals("¥35.80", AmountUtil.format(3580L));
        assertEquals("¥0.00", AmountUtil.format(0L));
        assertEquals("¥0.05", AmountUtil.format(5L));
        assertEquals("¥10.10", AmountUtil.format(1010L));
    }

    @Test
    public void format_groupsThousands() {
        assertEquals("¥1,280.00", AmountUtil.format(128000L));
        assertEquals("¥1,000,000.00", AmountUtil.format(100000000L));
        assertEquals("¥12,345,678.90", AmountUtil.format(1234567890L));
    }

    @Test
    public void format_putsMinusBeforeSymbolForNegative() {
        assertEquals("-¥1,280.00", AmountUtil.format(-128000L));
        assertEquals("-¥0.01", AmountUtil.format(-1L));
    }

    @Test
    public void formatSigned_prefixesByTransactionType() {
        assertEquals("-¥35.80", AmountUtil.formatSigned(3580L, false));
        assertEquals("+¥35.80", AmountUtil.formatSigned(3580L, true));
    }

    @Test
    public void formatPlain_omitsSymbolAndSign() {
        assertEquals("1,280.00", AmountUtil.formatPlain(128000L));
        assertEquals("1,280.00", AmountUtil.formatPlain(-128000L));
    }

    @Test
    public void toInputText_isAcceptedByParseToCents() {
        assertEquals("35.80", AmountUtil.toInputText(3580L));
        assertEquals("0.05", AmountUtil.toInputText(5L));
        assertEquals("1280.00", AmountUtil.toInputText(128000L));

        for (long cents : new long[]{0L, 1L, 99L, 100L, 3580L, 128000L, 99999999999L}) {
            assertEquals(cents, AmountUtil.parseToCents(AmountUtil.toInputText(cents)));
        }
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    @Test
    public void parseToCents_handlesIntegerAndDecimalInput() {
        assertEquals(3580L, AmountUtil.parseToCents("35.8"));
        assertEquals(3500L, AmountUtil.parseToCents("35"));
        assertEquals(3585L, AmountUtil.parseToCents("35.85"));
        assertEquals(0L, AmountUtil.parseToCents("0"));
        assertEquals(5L, AmountUtil.parseToCents(".05"));
        assertEquals(3580L, AmountUtil.parseToCents("  35.80  "));
    }

    @Test
    public void parseToCents_roundsHalfUpBeyondTwoDecimals() {
        assertEquals(1L, AmountUtil.parseToCents("0.005"));
        assertEquals(0L, AmountUtil.parseToCents("0.004"));
        assertEquals(3586L, AmountUtil.parseToCents("35.855"));
    }

    @Test
    public void parseToCents_rejectsBlankNegativeAndGarbage() {
        assertEquals(AmountUtil.INVALID, AmountUtil.parseToCents(null));
        assertEquals(AmountUtil.INVALID, AmountUtil.parseToCents(""));
        assertEquals(AmountUtil.INVALID, AmountUtil.parseToCents("   "));
        assertEquals(AmountUtil.INVALID, AmountUtil.parseToCents("-5"));
        assertEquals(AmountUtil.INVALID, AmountUtil.parseToCents("abc"));
        assertEquals(AmountUtil.INVALID, AmountUtil.parseToCents("1.2.3"));
        assertEquals(AmountUtil.INVALID, AmountUtil.parseToCents("¥35.80"));
    }

    @Test
    public void parseToCents_enforcesUpperBound() {
        assertEquals(AmountUtil.MAX_CENTS, AmountUtil.parseToCents("999999999.99"));
        assertEquals(AmountUtil.INVALID, AmountUtil.parseToCents("1000000000"));
    }

    /**
     * 0.1 + 0.2 是二进制浮点的经典陷阱，这里验证以「分」为单位相加后展示结果精确。
     */
    @Test
    public void centsArithmetic_hasNoFloatingPointDrift() {
        long sum = AmountUtil.parseToCents("0.1") + AmountUtil.parseToCents("0.2");
        assertEquals(30L, sum);
        assertEquals("¥0.30", AmountUtil.format(sum));

        long total = 0L;
        for (int i = 0; i < 10; i++) {
            total += AmountUtil.parseToCents("0.01");
        }
        assertEquals("¥0.10", AmountUtil.format(total));
    }

    // ------------------------------------------------------------------
    // 图表坐标轴的紧凑格式
    // ------------------------------------------------------------------

    @Test
    public void abbreviate_scalesByMagnitude() {
        assertEquals("¥0", AmountUtil.abbreviate(0L));
        assertEquals("¥980", AmountUtil.abbreviate(98000L));
        assertEquals("¥1k", AmountUtil.abbreviate(100000L));
        assertEquals("¥1.2k", AmountUtil.abbreviate(120000L));
        assertEquals("¥123.4k", AmountUtil.abbreviate(12345600L));
        assertEquals("¥1M", AmountUtil.abbreviate(100000000L));
        assertEquals("¥3.6M", AmountUtil.abbreviate(360000000L));
        assertEquals("-¥1.2k", AmountUtil.abbreviate(-120000L));
    }

    @Test
    public void symbol_isFullWidthYen() {
        assertEquals("\u00A5", AmountUtil.SYMBOL);
        assertTrue(AmountUtil.format(1L).startsWith("¥"));
    }
}
