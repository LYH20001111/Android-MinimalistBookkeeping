package com.skyanchor.bookkeeping.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.util.DateUtil;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 离线规则解析器单测（V5 基线第 52 章的测试用例清单）。
 *
 * <p>固定「现在」为 2026-09-25 10:30（周五），相对日期断言以此为基准。
 */
public class LocalRuleTransactionParserTest {

    /** 固定当前时刻：2026-09-25 10:30:00。 */
    private static final long NOW =
            DateUtil.dayMillisOf(2026, 9, 25) + 10L * 3_600_000L + 30L * 60_000L;

    private LocalRuleTransactionParser parser;
    private List<CategoryEntity> categories;

    @Before
    public void setUp() {
        parser = new LocalRuleTransactionParser(() -> NOW);
        categories = new ArrayList<>();
        // 与 DefaultData 预置分类同名同型（id 随意，解析层只认名字）。
        categories.add(new CategoryEntity("餐饮", "🍚", CategoryEntity.TYPE_EXPENSE, 1, true));
        categories.add(new CategoryEntity("交通", "🚌", CategoryEntity.TYPE_EXPENSE, 2, true));
        categories.add(new CategoryEntity("购物", "🛒", CategoryEntity.TYPE_EXPENSE, 3, true));
        categories.add(new CategoryEntity("娱乐", "🎮", CategoryEntity.TYPE_EXPENSE, 4, true));
        categories.add(new CategoryEntity("教育", "📚", CategoryEntity.TYPE_EXPENSE, 5, true));
        categories.add(new CategoryEntity("其他", "📦", CategoryEntity.TYPE_EXPENSE, 6, true));
        categories.add(new CategoryEntity("工资", "💰", CategoryEntity.TYPE_INCOME, 1, true));
        categories.add(new CategoryEntity("红包", "🧧", CategoryEntity.TYPE_INCOME, 2, true));
        categories.add(new CategoryEntity("其他", "📦", CategoryEntity.TYPE_INCOME, 3, true));
        // @Ignore 构造器不带 id；解析层按 id 回填草稿，这里手工编号以便断言。
        long id = 1;
        for (CategoryEntity category : categories) {
            category.id = id++;
        }
    }

    private CategoryEntity categoryOf(TransactionDraft draft) {
        for (CategoryEntity category : categories) {
            if (category.id == draft.categoryId) {
                return category;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 正例（基线第 52 章「AI 文本」）
    // ------------------------------------------------------------------

    @Test
    public void lunchTodayWithUnit() {
        TransactionDraft draft = parser.parseSync("今天中午吃饭花了32块", categories);
        assertEquals(CategoryEntity.TYPE_EXPENSE, draft.type);
        assertEquals(3200L, draft.amount);
        assertEquals(DateUtil.dayMillisOf(2026, 9, 25), draft.date);
        assertEquals("餐饮", categoryOf(draft).name);
        assertEquals(TransactionDraft.CONFIDENCE_HIGH, draft.amountConfidence);
    }

    @Test
    public void lunchYesterday() {
        TransactionDraft draft = parser.parseSync("昨天午餐32", categories);
        assertEquals(3200L, draft.amount);
        assertEquals(DateUtil.dayMillisOf(2026, 9, 24), draft.date);
        assertEquals("餐饮", categoryOf(draft).name);
    }

    @Test
    public void dinnerLastNightBareAmount() {
        TransactionDraft draft = parser.parseSync("昨晚吃饭128", categories);
        assertEquals(12800L, draft.amount);
        assertEquals(DateUtil.dayMillisOf(2026, 9, 24), draft.date);
        assertEquals("餐饮", categoryOf(draft).name);
    }

    @Test
    public void salaryIncome() {
        TransactionDraft draft = parser.parseSync("工资到账8500", categories);
        assertEquals(CategoryEntity.TYPE_INCOME, draft.type);
        assertEquals(850000L, draft.amount);
        assertEquals("工资", categoryOf(draft).name);
    }

    @Test
    public void buyHeadphone() {
        TransactionDraft draft = parser.parseSync("昨天买了个耳机599", categories);
        assertEquals(CategoryEntity.TYPE_EXPENSE, draft.type);
        assertEquals(59900L, draft.amount);
        assertEquals(DateUtil.dayMillisOf(2026, 9, 24), draft.date);
        assertEquals("购物", categoryOf(draft).name);
    }

    @Test
    public void subwayWithUnit() {
        TransactionDraft draft = parser.parseSync("地铁6块", categories);
        assertEquals(600L, draft.amount);
        assertEquals("交通", categoryOf(draft).name);
    }

    @Test
    public void subwayThisMorning() {
        TransactionDraft draft = parser.parseSync("今天早上地铁花了6块", categories);
        assertEquals(600L, draft.amount);
        assertEquals(DateUtil.dayMillisOf(2026, 9, 25), draft.date);
        assertEquals("交通", categoryOf(draft).name);
        // 基线第 19 章示例：商家/品牌词能推出分类。
        TransactionDraft coffee = parser.parseSync("星巴克买了一杯咖啡35", categories);
        assertEquals(3500L, coffee.amount);
        assertEquals("餐饮", categoryOf(coffee).name);
    }

    // ------------------------------------------------------------------
    // 边界（基线第 52 章「AI 边界」：不得凭空生成精确金额）
    // ------------------------------------------------------------------

    @Test
    public void vagueAmountStaysEmpty() {
        TransactionDraft draft = parser.parseSync("花了一百多", categories);
        assertEquals(0L, draft.amount);
        assertEquals(TransactionDraft.CONFIDENCE_LOW, draft.amountConfidence);
        assertEquals(CategoryEntity.TYPE_EXPENSE, draft.type);
    }

    @Test
    public void noAmountAtAll() {
        TransactionDraft draft = parser.parseSync("昨天吃饭", categories);
        assertEquals(0L, draft.amount);
        assertEquals(DateUtil.dayMillisOf(2026, 9, 24), draft.date);
        assertEquals("餐饮", categoryOf(draft).name);
    }

    @Test
    public void vagueShopping() {
        TransactionDraft draft = parser.parseSync("买了东西", categories);
        assertEquals(0L, draft.amount);
        assertEquals("购物", categoryOf(draft).name);
    }

    @Test
    public void vagueSalary() {
        TransactionDraft draft = parser.parseSync("工资发了", categories);
        assertEquals(CategoryEntity.TYPE_INCOME, draft.type);
        assertEquals(0L, draft.amount);
    }

    // ------------------------------------------------------------------
    // 用户明确指定（基线第 20、21 章：AI 不能擅自改分类）
    // ------------------------------------------------------------------

    @Test
    public void explicitCategoryWins() {
        TransactionDraft draft = parser.parseSync("打车32，算交通", categories);
        assertEquals("交通", categoryOf(draft).name);
        assertEquals(TransactionDraft.CONFIDENCE_HIGH, draft.categoryConfidence);
        // 「书」按词典会命中教育，验证显式指定优先且不被覆盖。
        TransactionDraft book = parser.parseSync("买书 88 元，算教育", categories);
        assertEquals(8800L, book.amount);
        assertEquals("教育", categoryOf(book).name);
    }

    // ------------------------------------------------------------------
    // 小票文本（基线第 5.7 章示例）
    // ------------------------------------------------------------------

    @Test
    public void receiptText() {
        String receipt = "麦当劳\n2026/09/25 12:36\n午餐套餐\n42.00";
        TransactionDraft draft = parser.parseSync(receipt, categories);
        assertEquals(CategoryEntity.TYPE_EXPENSE, draft.type);
        assertEquals(4200L, draft.amount);
        assertEquals(DateUtil.dayMillisOf(2026, 9, 25), draft.date);
        assertEquals("12:36", draft.time);
        assertEquals("麦当劳", draft.merchant);
        assertTrue(draft.note != null && draft.note.contains("午餐套餐"));
    }

    @Test
    public void afternoonTimeParsing() {
        TransactionDraft draft = parser.parseSync("昨天下午3点开会花了50元", categories);
        assertEquals(5000L, draft.amount);
        assertEquals(DateUtil.dayMillisOf(2026, 9, 24), draft.date);
        assertEquals("15:00", draft.time);
    }

    @Test
    public void commaAmountAndIsoDate() {
        TransactionDraft draft = parser.parseSync("2026/09/20 在京东买了1,299元的显示器", categories);
        assertEquals(129900L, draft.amount);
        assertEquals(DateUtil.dayMillisOf(2026, 9, 20), draft.date);
        assertEquals("京东", draft.merchant);
    }

    @Test
    public void emptyTextThrows() {
        try {
            parser.parseSync("   \n  ", categories);
            fail("空文本应抛 PARSE_FAILED");
        } catch (AiException e) {
            assertEquals(AiException.Kind.PARSE_FAILED, e.kind);
        }
    }

    @Test
    public void fullWidthDigitsNormalized() {
        TransactionDraft draft = parser.parseSync("昨天打车花了３２块", categories);
        assertEquals(3200L, draft.amount);
    }

    // ------------------------------------------------------------------
    // 兜底
    // ------------------------------------------------------------------

    @Test
    public void unknownMerchantTextFallsBackToOther() {
        TransactionDraft draft = parser.parseSync("花了42块", categories);
        assertEquals(4200L, draft.amount);
        // 无任何分类线索 → 落「其他」，置信度低，确认页提示重选。
        assertEquals("其他", categoryOf(draft).name);
        assertEquals(TransactionDraft.CONFIDENCE_LOW, draft.categoryConfidence);
    }

    @Test
    public void missingMerchantIsNull() {
        TransactionDraft draft = parser.parseSync("花了42块", categories);
        assertNull(draft.merchant);
    }
}
