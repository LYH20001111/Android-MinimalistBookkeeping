package com.skyanchor.bookkeeping.data.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.util.DateUtil;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 搜索 / 筛选单元测试（V2 开发计划 Phase 4 验收）。
 *
 * <p>{@link SearchFilter#matches} 是 {@code TransactionDao.search} 的 SQL WHERE 语义的可执行规格，
 * 这里在 JVM 上直接验证四条验收点：
 * <ol>
 *   <li>关键词命中备注 / 分类名 / 账户名（含转入账户名）；</li>
 *   <li>组合筛选（关键词 + 类型 + 分类 + 账户 + 金额 + 日期取交集）；</li>
 *   <li>金额边界（闭区间，端点命中）；</li>
 *   <li>结果统计与列表一致（{@link SearchResult} 的合计与过滤后列表同源，转账不计收支、仅计笔数）。</li>
 * </ol>
 */
public class TransactionSearchFilterTest {

    private static final long DAY = DateUtil.dayMillisOf(2024, 5, 15);
    private static final long DAY_NEXT = DateUtil.dayMillisOf(2024, 5, 16);

    private static final long CAT_FOOD = 1L;
    private static final long CAT_TRAFFIC = 2L;

    private static final long ACCOUNT_CASH = 1L;
    private static final long ACCOUNT_WECHAT = 2L;

    private static TransactionItem item(long id, int type, long amount, long date) {
        TransactionItem t = new TransactionItem();
        t.id = id;
        t.type = type;
        t.amount = amount;
        t.date = date;
        t.time = "12:00";
        return t;
    }

    private static TransactionItem expense(long id, long amount, long categoryId, String categoryName,
                                           String note, Long accountId, String accountName) {
        TransactionItem t = item(id, CategoryEntity.TYPE_EXPENSE, amount, DAY);
        t.categoryId = categoryId;
        t.categoryName = categoryName;
        t.note = note;
        t.accountId = accountId;
        t.accountName = accountName;
        return t;
    }

    private static TransactionItem transfer(long id, long amount, long fromId, String fromName,
                                            long toId, String toName, String note) {
        TransactionItem t = item(id, CategoryEntity.TYPE_TRANSFER, amount, DAY);
        t.categoryId = 0L;
        t.accountId = fromId;
        t.accountName = fromName;
        t.transferAccountId = toId;
        t.transferAccountName = toName;
        t.note = note;
        return t;
    }

    /** 在 JVM 上复现 DAO 的过滤：保留 matches 命中的条目，顺序不变。 */
    private static List<TransactionItem> apply(List<TransactionItem> items, SearchFilter filter) {
        List<TransactionItem> result = new ArrayList<>();
        for (TransactionItem t : items) {
            if (filter.matches(t)) {
                result.add(t);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 1. 关键词命中备注 / 分类名 / 账户名 / 转入账户名
    // ------------------------------------------------------------------

    @Test
    public void matches_keywordHitsNote() {
        TransactionItem t = expense(1L, 3500L, CAT_FOOD, "餐饮", "麻辣烫午餐", ACCOUNT_CASH, "现金");
        assertTrue(new SearchFilter.Builder().keyword("麻辣").build().matches(t));
        assertFalse(new SearchFilter.Builder().keyword("无关").build().matches(t));
    }

    @Test
    public void matches_keywordHitsCategoryName() {
        TransactionItem t = expense(1L, 3500L, CAT_FOOD, "餐饮", null, ACCOUNT_CASH, "现金");
        assertTrue(new SearchFilter.Builder().keyword("餐").build().matches(t));
    }

    @Test
    public void matches_keywordHitsAccountName() {
        TransactionItem t = expense(1L, 3500L, CAT_FOOD, "餐饮", null, ACCOUNT_WECHAT, "微信钱包");
        assertTrue(new SearchFilter.Builder().keyword("微信").build().matches(t));
    }

    /** 转账的转入账户名同样参与关键词匹配（钱去哪儿了也能搜到）。 */
    @Test
    public void matches_keywordHitsTransferAccountName() {
        TransactionItem t = transfer(1L, 50000L, ACCOUNT_CASH, "现金", ACCOUNT_WECHAT, "支付宝", null);
        assertTrue(new SearchFilter.Builder().keyword("支付宝").build().matches(t));
    }

    /** 关键词大小写不敏感，与 SQLite LIKE 的 ASCII 折叠一致。 */
    @Test
    public void matches_keywordIsCaseInsensitive() {
        TransactionItem t = expense(1L, 3500L, CAT_FOOD, "Coffee", "Starbucks", ACCOUNT_CASH, "现金");
        assertTrue(new SearchFilter.Builder().keyword("star").build().matches(t));
        assertTrue(new SearchFilter.Builder().keyword("COFFEE").build().matches(t));
    }

    /** 空 / 纯空白关键词归一为「不限」，命中一切。 */
    @Test
    public void matches_blankKeywordMatchesAll() {
        TransactionItem t = expense(1L, 3500L, CAT_FOOD, "餐饮", "午餐", ACCOUNT_CASH, "现金");
        assertTrue(new SearchFilter.Builder().keyword(null).build().matches(t));
        assertTrue(new SearchFilter.Builder().keyword("   ").build().matches(t));
        assertFalse(new SearchFilter.Builder().keyword("   ").build().hasKeyword());
    }

    // ------------------------------------------------------------------
    // 2. 类型 / 分类 / 账户筛选
    // ------------------------------------------------------------------

    @Test
    public void matches_typeFilterKeepsOnlySelected() {
        TransactionItem exp = expense(1L, 100L, CAT_FOOD, "餐饮", null, ACCOUNT_CASH, "现金");
        TransactionItem inc = item(2L, CategoryEntity.TYPE_INCOME, 200L, DAY);
        TransactionItem trf = transfer(3L, 300L, ACCOUNT_CASH, "现金", ACCOUNT_WECHAT, "微信", null);

        SearchFilter expenseOnly = new SearchFilter.Builder().types(true, false, false).build();
        assertTrue(expenseOnly.matches(exp));
        assertFalse(expenseOnly.matches(inc));
        assertFalse(expenseOnly.matches(trf));

        // 默认（三者全 true）不限类型
        SearchFilter all = SearchFilter.all();
        assertTrue(all.matches(exp) && all.matches(inc) && all.matches(trf));
    }

    @Test
    public void matches_categoryFilter() {
        TransactionItem food = expense(1L, 100L, CAT_FOOD, "餐饮", null, ACCOUNT_CASH, "现金");
        TransactionItem traffic = expense(2L, 100L, CAT_TRAFFIC, "交通", null, ACCOUNT_CASH, "现金");
        SearchFilter byFood = new SearchFilter.Builder().categoryId(CAT_FOOD).build();
        assertTrue(byFood.matches(food));
        assertFalse(byFood.matches(traffic));

        // categoryId=0 不限；转账 categoryId 归 0，选具体分类时自然不命中
        assertTrue(new SearchFilter.Builder().categoryId(SearchFilter.NO_CATEGORY).build().matches(traffic));
        TransactionItem trf = transfer(3L, 100L, ACCOUNT_CASH, "现金", ACCOUNT_WECHAT, "微信", null);
        assertFalse(byFood.matches(trf));
    }

    /** 账户过滤命中转出或转入任一端，转账两端都算「涉及该账户」。 */
    @Test
    public void matches_accountFilterHitsEitherEnd() {
        TransactionItem trf = transfer(1L, 50000L, ACCOUNT_CASH, "现金", ACCOUNT_WECHAT, "微信", null);
        assertTrue(new SearchFilter.Builder().accountId(ACCOUNT_CASH).build().matches(trf));
        assertTrue(new SearchFilter.Builder().accountId(ACCOUNT_WECHAT).build().matches(trf));
        assertFalse(new SearchFilter.Builder().accountId(99L).build().matches(trf));

        TransactionItem exp = expense(2L, 100L, CAT_FOOD, "餐饮", null, ACCOUNT_CASH, "现金");
        assertTrue(new SearchFilter.Builder().accountId(ACCOUNT_CASH).build().matches(exp));
        assertFalse(new SearchFilter.Builder().accountId(ACCOUNT_WECHAT).build().matches(exp));
    }

    // ------------------------------------------------------------------
    // 3. 金额边界（闭区间）与日期区间
    // ------------------------------------------------------------------

    @Test
    public void matches_amountRangeIsInclusive() {
        TransactionItem t = expense(1L, 5000L, CAT_FOOD, "餐饮", null, ACCOUNT_CASH, "现金");
        // [10.00, 50.00] 元 = [1000, 5000] 分
        SearchFilter range = new SearchFilter.Builder().amountRange(1000L, 5000L).build();
        assertTrue(range.matches(t));                       // 命中上界
        assertTrue(range.matches(expense(2L, 1000L, CAT_FOOD, "餐饮", null, ACCOUNT_CASH, "现金")));  // 命中下界
        assertFalse(range.matches(expense(3L, 999L, CAT_FOOD, "餐饮", null, ACCOUNT_CASH, "现金")));  // 低于下界
        assertFalse(range.matches(expense(4L, 5001L, CAT_FOOD, "餐饮", null, ACCOUNT_CASH, "现金"))); // 超过上界

        // 默认不限金额
        assertTrue(SearchFilter.all().matches(expense(5L, 1L, CAT_FOOD, "餐饮", null, ACCOUNT_CASH, "现金")));
    }

    @Test
    public void matches_dateRangeIsInclusive() {
        TransactionItem today = item(1L, CategoryEntity.TYPE_EXPENSE, 100L, DAY);
        TransactionItem next = item(2L, CategoryEntity.TYPE_EXPENSE, 100L, DAY_NEXT);
        SearchFilter range = new SearchFilter.Builder().dateRange(DAY, DAY).build();
        assertTrue(range.matches(today));
        assertFalse(range.matches(next));
        // 默认不限日期
        assertTrue(SearchFilter.all().matches(next));
    }

    // ------------------------------------------------------------------
    // 组合筛选：所有条件取交集
    // ------------------------------------------------------------------

    @Test
    public void matches_combinedFiltersIntersect() {
        List<TransactionItem> items = Arrays.asList(
                expense(1L, 3500L, CAT_FOOD, "餐饮", "麻辣烫", ACCOUNT_WECHAT, "微信钱包"),
                expense(2L, 3500L, CAT_FOOD, "餐饮", "麻辣烫", ACCOUNT_CASH, "现金"),
                expense(3L, 900L, CAT_TRAFFIC, "交通", "打车", ACCOUNT_WECHAT, "微信钱包"),
                transfer(4L, 3500L, ACCOUNT_WECHAT, "微信钱包", ACCOUNT_CASH, "现金", "麻辣烫还款"));

        // 关键词「麻辣」+ 仅支出 + 分类餐饮 + 账户微信 + 金额 [10, 50] 元
        SearchFilter filter = new SearchFilter.Builder()
                .keyword("麻辣")
                .types(true, false, false)
                .categoryId(CAT_FOOD)
                .accountId(ACCOUNT_WECHAT)
                .amountRange(1000L, 5000L)
                .build();

        List<TransactionItem> hits = apply(items, filter);
        assertEquals(1, hits.size());
        assertEquals(1L, hits.get(0).id);   // 只有「微信 + 餐饮 + 麻辣烫 + 35.00 元 + 支出」全中
    }

    // ------------------------------------------------------------------
    // 4. 结果统计与列表一致
    // ------------------------------------------------------------------

    @Test
    public void searchResult_summaryIsConsistentWithItems() {
        List<TransactionItem> items = Arrays.asList(
                expense(1L, 3500L, CAT_FOOD, "餐饮", null, ACCOUNT_CASH, "现金"),
                expense(2L, 1500L, CAT_TRAFFIC, "交通", null, ACCOUNT_CASH, "现金"),
                item(3L, CategoryEntity.TYPE_INCOME, 100000L, DAY),
                transfer(4L, 50000L, ACCOUNT_CASH, "现金", ACCOUNT_WECHAT, "微信", null));

        // 过滤后：全部类型（默认）→ 4 条都在
        SearchResult result = SearchResult.of(apply(items, SearchFilter.all()));

        assertEquals(4, result.items.size());
        assertEquals(4, result.summary.count);          // 笔数含转账
        assertEquals(5000L, result.summary.expense);    // 支出 35.00 + 15.00，不含转账
        assertEquals(100000L, result.summary.income);   // 收入不含转账
        assertEquals(95000L, result.summary.balance());
        assertFalse(result.isEmpty());
    }

    /** 只筛支出时，合计与列表都只剩支出，转账与收入被排除。 */
    @Test
    public void searchResult_onlyExpenseFilter() {
        List<TransactionItem> items = Arrays.asList(
                expense(1L, 3500L, CAT_FOOD, "餐饮", null, ACCOUNT_CASH, "现金"),
                item(2L, CategoryEntity.TYPE_INCOME, 100000L, DAY),
                transfer(3L, 50000L, ACCOUNT_CASH, "现金", ACCOUNT_WECHAT, "微信", null));

        SearchFilter expenseOnly = new SearchFilter.Builder().types(true, false, false).build();
        SearchResult result = SearchResult.of(apply(items, expenseOnly));

        assertEquals(1, result.items.size());
        assertEquals(1, result.summary.count);
        assertEquals(3500L, result.summary.expense);
        assertEquals(0L, result.summary.income);
    }

    @Test
    public void searchResult_ofNullOrEmptyIsEmpty() {
        assertTrue(SearchResult.of(null).isEmpty());
        assertTrue(SearchResult.of(new ArrayList<TransactionItem>()).isEmpty());
        assertEquals(0, SearchResult.of(null).summary.count);
        assertEquals(0L, SearchResult.of(null).summary.expense);
        assertEquals(0L, SearchResult.of(null).summary.income);
    }

    // ------------------------------------------------------------------
    // 过滤器值语义：默认不限、关键词归一、按值相等
    // ------------------------------------------------------------------

    @Test
    public void all_isUnrestricted() {
        assertTrue(SearchFilter.all().isUnrestricted());
        assertFalse(new SearchFilter.Builder().keyword("x").build().isUnrestricted());
        assertFalse(new SearchFilter.Builder().types(true, false, true).build().isUnrestricted());
        assertFalse(new SearchFilter.Builder().categoryId(CAT_FOOD).build().isUnrestricted());
        assertFalse(new SearchFilter.Builder().amountRange(1L, 2L).build().isUnrestricted());
    }

    @Test
    public void builder_normalizesBlankKeywordToNull() {
        assertEquals(null, new SearchFilter.Builder().keyword("  ").build().keyword);
        assertEquals("午餐", new SearchFilter.Builder().keyword("  午餐 ").build().keyword);
    }

    @Test
    public void filter_equalsByValue() {
        SearchFilter a = new SearchFilter.Builder().keyword("x").categoryId(CAT_FOOD).build();
        SearchFilter b = new SearchFilter.Builder().keyword("x").categoryId(CAT_FOOD).build();
        SearchFilter c = new SearchFilter.Builder().keyword("y").categoryId(CAT_FOOD).build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c));
        // toBuilder 复制后按值相等
        assertEquals(a, a.toBuilder().build());
    }
}
