package com.skyanchor.bookkeeping.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.DateUtil;

import org.junit.Test;

/**
 * 草稿校验层单测（V5 基线第 25 章）：非法输出必须被修正，可疑字段必须被标记。
 */
public class DraftValidatorTest {

    private static final long NOW = DateUtil.dayMillisOf(2026, 9, 25) + 8L * 3_600_000L;

    @Test
    public void negativeAmountCleared() {
        TransactionDraft draft = new TransactionDraft();
        draft.amount = -300L;
        DraftValidator.normalize(draft, NOW);
        assertEquals(0L, draft.amount);
        assertFalse(draft.hasAmount());
    }

    @Test
    public void oversizeAmountCleared() {
        TransactionDraft draft = new TransactionDraft();
        draft.amount = AmountUtil.INVALID;
        DraftValidator.normalize(draft, NOW);
        assertEquals(0L, draft.amount);
    }

    @Test
    public void transferTypeFallsBackToExpense() {
        TransactionDraft draft = new TransactionDraft();
        draft.type = CategoryEntity.TYPE_TRANSFER;
        DraftValidator.normalize(draft, NOW);
        assertEquals(CategoryEntity.TYPE_EXPENSE, draft.type);
    }

    @Test
    public void futureDateClampedToToday() {
        TransactionDraft draft = new TransactionDraft();
        draft.date = DateUtil.dayMillisOf(2030, 1, 1);
        DraftValidator.normalize(draft, NOW);
        assertEquals(DateUtil.dayMillisOf(2026, 9, 25), draft.date);
    }

    @Test
    public void zeroDateDefaultsToToday() {
        TransactionDraft draft = new TransactionDraft();
        DraftValidator.normalize(draft, NOW);
        assertEquals(DateUtil.dayMillisOf(2026, 9, 25), draft.date);
    }

    @Test
    public void invalidTimeDefaultsToNow() {
        TransactionDraft draft = new TransactionDraft();
        draft.time = "99:99";
        DraftValidator.normalize(draft, NOW);
        assertEquals("08:00", draft.time);

        draft.time = "abc";
        DraftValidator.normalize(draft, NOW);
        assertEquals("08:00", draft.time);

        draft.time = null;
        DraftValidator.normalize(draft, NOW);
        assertEquals("08:00", draft.time);
    }

    @Test
    public void validTimeKept() {
        TransactionDraft draft = new TransactionDraft();
        draft.time = "12:36";
        DraftValidator.normalize(draft, NOW);
        assertEquals("12:36", draft.time);
    }

    @Test
    public void confidenceClamped() {
        TransactionDraft draft = new TransactionDraft();
        draft.confidence = 5000;
        draft.amountConfidence = -10;
        DraftValidator.normalize(draft, NOW);
        assertEquals(1000, draft.confidence);
        assertEquals(0, draft.amountConfidence);
    }

    @Test
    public void savableRequiresPositiveAmount() {
        TransactionDraft draft = new TransactionDraft();
        assertFalse(DraftValidator.isSavable(draft));
        draft.amount = 3200L;
        draft.date = DateUtil.dayMillisOf(2026, 9, 25);
        assertTrue(DraftValidator.isSavable(draft));
    }

    @Test
    public void merchantMergedIntoNote() {
        assertEquals("麦当劳 午餐套餐",
                DraftValidator.mergeMerchantIntoNote("麦当劳", "午餐套餐"));
        // 备注里已含商家名时不重复。
        assertEquals("麦当劳套餐",
                DraftValidator.mergeMerchantIntoNote("麦当劳", "麦当劳套餐"));
        assertEquals("麦当劳", DraftValidator.mergeMerchantIntoNote("麦当劳", null));
        assertEquals("午餐套餐", DraftValidator.mergeMerchantIntoNote(null, "午餐套餐"));
        assertNull(DraftValidator.mergeMerchantIntoNote(null, null));
        assertNull(DraftValidator.mergeMerchantIntoNote("  ", "  "));
    }
}
