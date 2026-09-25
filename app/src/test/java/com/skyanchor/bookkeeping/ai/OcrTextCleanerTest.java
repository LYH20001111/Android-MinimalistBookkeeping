package com.skyanchor.bookkeeping.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * OCR 文本清洗单测（V5）：只滤噪音行，金额行与日期行必须原样保留。
 */
public class OcrTextCleanerTest {

    private OcrService.OcrLine line(String text) {
        return new OcrService.OcrLine(text, 0.9f);
    }

    @Test
    public void keepsAmountAndDateLines() {
        List<OcrService.OcrLine> lines = Arrays.asList(
                line("麦当劳"),
                line("2026/09/25 12:36"),
                line("午餐套餐"),
                line("42.00"));
        String cleaned = OcrTextCleaner.clean(lines);
        assertEquals("麦当劳\n2026/09/25 12:36\n午餐套餐\n42.00", cleaned);
    }

    @Test
    public void dropsSymbolOnlyLines() {
        List<OcrService.OcrLine> lines = Arrays.asList(
                line("----"),
                line("|"),
                line("·"),
                line("合计：42.00"));
        String cleaned = OcrTextCleaner.clean(lines);
        assertEquals("合计：42.00", cleaned);
    }

    @Test
    public void trimsAndSkipsEmpty() {
        List<OcrService.OcrLine> lines = Arrays.asList(
                line("  麦当劳  "),
                line("   "),
                line("42.00"));
        assertEquals("麦当劳\n42.00", OcrTextCleaner.clean(lines));
    }

    @Test
    public void allNoiseReturnsEmpty() {
        List<OcrService.OcrLine> lines = Collections.singletonList(line("--- ***"));
        assertEquals("", OcrTextCleaner.clean(lines));
    }

    @Test
    public void emptyInputReturnsEmpty() {
        assertEquals("", OcrTextCleaner.clean(new ArrayList<>()));
    }

    @Test
    public void singleCharLineDropped() {
        // 单个字符（无论汉字还是字母数字）几乎都是 OCR 噪音，过滤掉。
        assertFalse(OcrTextCleaner.keep("A"));
        assertFalse(OcrTextCleaner.keep("餐"));
        assertTrue(OcrTextCleaner.keep("AB"));
        assertTrue(OcrTextCleaner.keep("合计"));
    }
}
