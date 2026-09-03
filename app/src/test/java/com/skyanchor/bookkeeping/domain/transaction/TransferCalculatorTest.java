package com.skyanchor.bookkeeping.domain.transaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.data.model.PeriodSummary;
import com.skyanchor.bookkeeping.data.model.RecordListItem;
import com.skyanchor.bookkeeping.domain.account.CalculateAccountBalanceUseCase;
import com.skyanchor.bookkeeping.util.DateUtil;
import com.skyanchor.bookkeeping.util.StatisticsCalculator;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 转账计算单元测试（V2 关键数据模型决策 #6，开发计划 Phase 3 验收）。
 *
 * <p>转账（type=3）只在两个账户之间搬动余额，覆盖计划要求的四条不变量：
 * <ol>
 *   <li>转账不污染收支统计——既不计收入也不计支出；</li>
 *   <li>两账户余额此消彼长——转出方减少、转入方增加，金额相等；</li>
 *   <li>总资产不变——转账前后两账户余额之和相等；</li>
 *   <li>两账户相同被拒——{@link TransferValidator} 只放行「都已选且互不相同」。</li>
 * </ol>
 *
 * <p>全部走纯函数（{@link StatisticsCalculator} / {@link CalculateAccountBalanceUseCase#compute} /
 * {@link TransferValidator}），不依赖 Android 环境与数据库。
 */
public class TransferCalculatorTest {

    private static final long MAY_1 = DateUtil.dayMillisOf(2024, 5, 1);
    private static final long MAY_15 = DateUtil.dayMillisOf(2024, 5, 15);
    private static final long MAY_31 = DateUtil.dayMillisOf(2024, 5, 31);

    private static final long CAT_FOOD = 1L;
    private static final long CAT_SALARY = 11L;

    private static final long ACCOUNT_CASH = 1L;
    private static final long ACCOUNT_WECHAT = 2L;

    private static TransactionItem expense(long id, long amount, long date, long categoryId,
                                           String name) {
        TransactionItem item = new TransactionItem();
        item.id = id;
        item.type = CategoryEntity.TYPE_EXPENSE;
        item.amount = amount;
        item.date = date;
        item.time = "12:00";
        item.categoryId = categoryId;
        item.categoryName = name;
        item.categoryIcon = "💰";
        return item;
    }

    private static TransactionItem income(long id, long amount, long date, long categoryId,
                                          String name) {
        TransactionItem item = new TransactionItem();
        item.id = id;
        item.type = CategoryEntity.TYPE_INCOME;
        item.amount = amount;
        item.date = date;
        item.time = "12:00";
        item.categoryId = categoryId;
        item.categoryName = name;
        item.categoryIcon = "🧧";
        return item;
    }

    /** 转账：不归属分类（categoryId 归 0），只在转出 / 转入账户之间搬动余额。 */
    private static TransactionItem transfer(long id, long amount, long date, long fromAccount,
                                            long toAccount) {
        TransactionItem item = new TransactionItem();
        item.id = id;
        item.type = CategoryEntity.TYPE_TRANSFER;
        item.amount = amount;
        item.date = date;
        item.time = "12:00";
        item.categoryId = 0L;
        item.accountId = fromAccount;
        item.transferAccountId = toAccount;
        return item;
    }

    // ------------------------------------------------------------------
    // 1. 转账不污染收支统计
    // ------------------------------------------------------------------

    /** summary 遇转账：收支合计只来自收入 / 支出项，转账仅计入笔数。 */
    @Test
    public void summary_transferDoesNotPolluteIncomeOrExpense() {
        List<TransactionItem> items = Arrays.asList(
                expense(1L, 3580L, MAY_15, CAT_FOOD, "餐饮"),
                income(2L, 100000L, MAY_15, CAT_SALARY, "工资"),
                // 现金 → 微信 500.00 元：既不是收入也不是支出
                transfer(3L, 50000L, MAY_15, ACCOUNT_CASH, ACCOUNT_WECHAT));

        PeriodSummary summary = StatisticsCalculator.summary(items, MAY_1, MAY_31);

        assertEquals(100000L, summary.income);
        assertEquals(3580L, summary.expense);
        assertEquals(96420L, summary.balance());
        // 转账仍是一条真实记录，计入笔数
        assertEquals(3, summary.count);
    }

    /** 一个区间内只有转账时，收支与结余均为 0，但笔数反映转账、非空。 */
    @Test
    public void summary_onlyTransfersHasZeroIncomeAndExpense() {
        List<TransactionItem> items = Arrays.asList(
                transfer(1L, 50000L, MAY_15, ACCOUNT_CASH, ACCOUNT_WECHAT),
                transfer(2L, 20000L, MAY_15, ACCOUNT_WECHAT, ACCOUNT_CASH));

        PeriodSummary summary = StatisticsCalculator.summary(items, MAY_1, MAY_31);

        assertEquals(0L, summary.income);
        assertEquals(0L, summary.expense);
        assertEquals(0L, summary.balance());
        assertEquals(2, summary.count);
        assertFalse(summary.isEmpty());
    }

    /** groupByDay 遇转账：当日收支合计不含转账，但转账仍作为一行展示、计入当日笔数。 */
    @Test
    public void groupByDay_transferExcludedFromDayTotals() {
        List<TransactionItem> items = new ArrayList<>(Arrays.asList(
                expense(1L, 1000L, MAY_15, CAT_FOOD, "餐饮"),
                transfer(2L, 50000L, MAY_15, ACCOUNT_CASH, ACCOUNT_WECHAT),
                income(3L, 5000L, MAY_15, CAT_SALARY, "工资")));

        List<RecordListItem> rows = StatisticsCalculator.groupByDay(items, null);

        // 1 个 Header + 3 个 Row
        assertEquals(4, rows.size());
        RecordListItem.Header header = (RecordListItem.Header) rows.get(0);
        assertEquals(MAY_15, header.dayMillis);
        assertEquals(1000L, header.expense);
        assertEquals(5000L, header.income);
        // 转账计入当日笔数、且作为一行展示
        assertEquals(3, header.count);
        assertTrue(((RecordListItem.Row) rows.get(2)).item.isTransfer());
    }

    // ------------------------------------------------------------------
    // 2. 两账户余额此消彼长 + 3. 总资产不变
    // ------------------------------------------------------------------

    /**
     * 现金转出 500.00 元、微信转入同额：转出方余额减少、转入方增加，变化量相等且方向相反。
     * 这正是「此消彼长」——用 {@link CalculateAccountBalanceUseCase#compute} 独立验证两个账户。
     */
    @Test
    public void compute_transferMovesBalanceBetweenAccounts() {
        long cashInitial = 100000L;   // 现金 ¥1000.00
        long wechatInitial = 20000L;  // 微信 ¥200.00
        long amount = 50000L;         // 转账 ¥500.00

        // 转出方：transferOut 命中；转入方：transferIn 命中
        long cashAfter = CalculateAccountBalanceUseCase.compute(cashInitial, 0L, 0L, 0L, amount);
        long wechatAfter = CalculateAccountBalanceUseCase.compute(
                wechatInitial, 0L, 0L, amount, 0L);

        assertEquals(50000L, cashAfter);    // 现金减少 500.00
        assertEquals(70000L, wechatAfter);  // 微信增加 500.00
        // 此消彼长：一方减少多少，另一方就增加多少
        assertEquals(cashInitial - cashAfter, wechatAfter - wechatInitial);
        assertEquals(amount, cashInitial - cashAfter);
    }

    /** 总资产不变：转账前后两账户余额之和相等，钱只是换了个地方。 */
    @Test
    public void compute_transferKeepsTotalAssetsUnchanged() {
        long cashInitial = 100000L;
        long wechatInitial = 20000L;
        long amount = 50000L;

        long totalBefore = cashInitial + wechatInitial;
        long cashAfter = CalculateAccountBalanceUseCase.compute(cashInitial, 0L, 0L, 0L, amount);
        long wechatAfter = CalculateAccountBalanceUseCase.compute(
                wechatInitial, 0L, 0L, amount, 0L);
        long totalAfter = cashAfter + wechatAfter;

        assertEquals(totalBefore, totalAfter);
        assertEquals(120000L, totalAfter);
    }

    /** 往返转账（现金→微信→现金，同额）后，两账户都回到初始余额，总资产始终不变。 */
    @Test
    public void compute_roundTripTransferReturnsToOriginal() {
        long cashInitial = 100000L;
        long wechatInitial = 20000L;
        long amount = 50000L;

        // 现金转出 amount、又转回 amount：transferOut 与 transferIn 各命中一次
        long cashAfter = CalculateAccountBalanceUseCase.compute(
                cashInitial, 0L, 0L, amount, amount);
        long wechatAfter = CalculateAccountBalanceUseCase.compute(
                wechatInitial, 0L, 0L, amount, amount);

        assertEquals(cashInitial, cashAfter);
        assertEquals(wechatInitial, wechatAfter);
        assertEquals(cashInitial + wechatInitial, cashAfter + wechatAfter);
    }

    // ------------------------------------------------------------------
    // 4. 两账户相同被拒
    // ------------------------------------------------------------------

    /** 转出与转入撞成同一账户：isValid 为 false，且 isSameAccount 精确识别这一情形。 */
    @Test
    public void validator_rejectsSameAccount() {
        assertFalse(TransferValidator.isValid(ACCOUNT_CASH, ACCOUNT_CASH));
        assertTrue(TransferValidator.isSameAccount(ACCOUNT_CASH, ACCOUNT_CASH));
        assertFalse(TransferValidator.isValid(ACCOUNT_WECHAT, ACCOUNT_WECHAT));
        assertTrue(TransferValidator.isSameAccount(ACCOUNT_WECHAT, ACCOUNT_WECHAT));
    }

    /** 任一账户未选（id=0）都不合法，且不属于「同一账户」而是「未选择」。 */
    @Test
    public void validator_rejectsUnselectedAccount() {
        assertFalse(TransferValidator.isValid(0L, ACCOUNT_WECHAT));
        assertFalse(TransferValidator.isValid(ACCOUNT_CASH, 0L));
        assertFalse(TransferValidator.isValid(0L, 0L));
        // 未选与「撞成同一个」是两种不同的提示，isSameAccount 只对都已选且相同为 true
        assertFalse(TransferValidator.isSameAccount(0L, ACCOUNT_WECHAT));
        assertFalse(TransferValidator.isSameAccount(ACCOUNT_CASH, 0L));
        assertFalse(TransferValidator.isSameAccount(0L, 0L));
    }

    /** 两个互不相同的已选账户才是合法转账，且不被误判为同一账户。 */
    @Test
    public void validator_acceptsTwoDistinctAccounts() {
        assertTrue(TransferValidator.isValid(ACCOUNT_CASH, ACCOUNT_WECHAT));
        assertTrue(TransferValidator.isValid(ACCOUNT_WECHAT, ACCOUNT_CASH));
        assertFalse(TransferValidator.isSameAccount(ACCOUNT_CASH, ACCOUNT_WECHAT));
    }
}
