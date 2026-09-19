package com.skyanchor.bookkeeping.domain.account;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.skyanchor.bookkeeping.data.database.AccountDao;
import com.skyanchor.bookkeeping.data.database.AppDatabase;
import com.skyanchor.bookkeeping.data.database.TransactionDao;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.RefundRecordEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 账户余额一致性测试（V2 开发计划 Phase 10，需真机 / 模拟器）。
 *
 * <p>验证「余额缓存与 {@link CalculateAccountBalanceUseCase} 重算恒等」这条不变量：
 * 收入 / 支出 / 转出 / 转入 / 编辑 / 删除每一步之后，缓存列、SQL 联表重算投影
 * （{@code recalcBalance}）与 Java 侧重算（{@code calculate}）三者必须一致；
 * 人为制造缓存漂移后，{@link AccountBalanceValidator} 能以重算纠正。
 * 这里以 DAO 精确复现仓库层「写入交易 + 同事务更新缓存」的写序。
 */
@RunWith(AndroidJUnit4.class)
public class AccountBalanceConsistencyTest {

    private AppDatabase db;
    private AccountDao accountDao;
    private TransactionDao transactionDao;
    private CalculateAccountBalanceUseCase useCase;
    private AccountBalanceValidator validator;

    private long cashId;
    private long wechatId;
    private long foodCategoryId;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        accountDao = db.accountDao();
        transactionDao = db.transactionDao();
        useCase = new CalculateAccountBalanceUseCase(accountDao, transactionDao);
        validator = new AccountBalanceValidator(accountDao, useCase);

        CategoryEntity food = new CategoryEntity("餐饮", "🍜", CategoryEntity.TYPE_EXPENSE, 1,
                false);
        foodCategoryId = db.categoryDao().insert(food);

        cashId = insertAccount("现金", AccountEntity.TYPE_CASH, 100_00L);
        wechatId = insertAccount("微信", AccountEntity.TYPE_WECHAT, -50_00L);
    }

    @After
    public void tearDown() {
        db.close();
    }

    private long insertAccount(String name, int type, long initialBalance) {
        AccountEntity account = new AccountEntity(name, type, initialBalance, false, 1);
        return accountDao.insert(account);
    }

    private long insertTransaction(int type, long amount, long accountId, Long transferAccountId) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.type = type;
        transaction.amount = amount;
        transaction.categoryId = type == CategoryEntity.TYPE_TRANSFER
                ? null : Long.valueOf(foodCategoryId);
        transaction.accountId = accountId;
        transaction.transferAccountId = transferAccountId;
        transaction.date = 1_700_000_000_000L;
        transaction.time = "12:00";
        transaction.createdAt = 1L;
        transaction.updatedAt = 1L;
        long id = transactionDao.insert(transaction);
        // 与仓库层同写序：写入交易后在同一事务内对齐缓存（测试中逐笔对齐）
        accountDao.updateBalance(accountId, useCase.calculate(accountId), 1L);
        if (transferAccountId != null) {
            accountDao.updateBalance(transferAccountId, useCase.calculate(transferAccountId), 1L);
        }
        return id;
    }

    private void assertInvariant(long accountId, long expected) {
        AccountEntity account = accountDao.getById(accountId);
        assertEquals(expected, account.balance);
        assertEquals(expected, accountDao.recalcBalance(accountId));
        assertEquals(expected, useCase.calculate(accountId));
    }

    /** 收 → 支 → 转出 → 转入 → 编辑 → 删除，每一步后缓存与两种重算恒等。 */
    @Test
    public void balanceCache_matchesRecomputeAfterEveryWrite() {
        long initial = 100_00L;
        assertInvariant(cashId, initial);

        // 1) 收入 +500
        long incomeId = insertTransaction(CategoryEntity.TYPE_INCOME, 500_00L, cashId, null);
        assertInvariant(cashId, initial + 500_00L);

        // 2) 支出 -200
        long expenseId = insertTransaction(CategoryEntity.TYPE_EXPENSE, 200_00L, cashId, null);
        assertInvariant(cashId, initial + 300_00L);

        // 3) 转出现金 → 微信 +100：现金减、微信增，总资产不变
        long transferId = insertTransaction(CategoryEntity.TYPE_TRANSFER, 100_00L, cashId,
                wechatId);
        assertInvariant(cashId, initial + 200_00L);
        assertInvariant(wechatId, -50_00L + 100_00L);

        // 4) 编辑：支出金额 200 → 300，现金再减 100
        TransactionEntity expense = transactionDao.getEntityById(expenseId);
        expense.amount = 300_00L;
        transactionDao.update(expense);
        accountDao.updateBalance(cashId, useCase.calculate(cashId), 1L);
        assertInvariant(cashId, initial + 100_00L);

        // 5) 删除收入 500
        transactionDao.deleteById(incomeId);
        accountDao.updateBalance(cashId, useCase.calculate(cashId), 1L);
        assertInvariant(cashId, initial - 400_00L);

        // 6) 删除转账：现金回升、微信回落，总资产只剩那笔支出的影响
        transactionDao.deleteById(transferId);
        accountDao.updateBalance(cashId, useCase.calculate(cashId), 1L);
        accountDao.updateBalance(wechatId, useCase.calculate(wechatId), 1L);
        assertInvariant(cashId, initial - 300_00L);
        assertInvariant(wechatId, -50_00L);

        // 总资产恒等：初始总资产 50_00（现金 100_00 + 微信 -50_00），
        // 全部写入与回退后只剩一笔支出 300_00 → 合计 -250_00（转账不改变总资产）
        assertEquals(50_00L - 300_00L,
                accountDao.recalcBalance(cashId) + accountDao.recalcBalance(wechatId));
    }

    /** 信用卡负余额（欠款）也是同一模型：初始 -100 + 收入 150 → 余额 +50。 */
    @Test
    public void creditAccount_negativeBalanceIsSameModel() {
        long creditId = insertAccount("信用卡", AccountEntity.TYPE_CREDIT, -100_00L);
        insertTransaction(CategoryEntity.TYPE_INCOME, 150_00L, creditId, null);
        assertInvariant(creditId, 50_00L);
    }

    /** 缓存被人为污染时，校验器必须以重算纠正（返回被纠正的账户数）。 */
    @Test
    public void validator_fixesCacheDrift() {
        insertTransaction(CategoryEntity.TYPE_INCOME, 500_00L, cashId, null);
        long expected = useCase.calculate(cashId);

        // 人为把缓存写错
        accountDao.updateBalance(cashId, 999_999L, 1L);
        assertEquals(999_999L, accountDao.getById(cashId).balance);

        assertEquals(1, validator.validateAndFixAll());
        assertEquals(expected, accountDao.getById(cashId).balance);
        assertEquals(expected, accountDao.recalcBalance(cashId));

        // 再次校验：已一致，无纠正
        assertEquals(0, validator.validateAndFixAll());
    }

    // ------------------------------------------------------------------
    // V2.1：历史账单账户归属（基线 28.4，需真机 / 模拟器）
    // ------------------------------------------------------------------

    /** 插入一笔未归属历史账单（account_id IS NULL，V1 迁移形态），不做缓存对齐。 */
    private long insertUnassigned(int type, long amount) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.type = type;
        transaction.amount = amount;
        transaction.categoryId = Long.valueOf(foodCategoryId);
        transaction.accountId = null;
        transaction.transferAccountId = null;
        transaction.date = 1_700_000_000_000L;
        transaction.time = "12:00";
        transaction.createdAt = 1L;
        transaction.updatedAt = 1L;
        return transactionDao.insert(transaction);
    }

    /**
     * 未归属历史账单：归属前不影响账户余额；批量归属后计入目标账户余额，
     * 其余账户与全局支出统计不变（V2.1 基线 28.4 的四条验收）。
     */
    @Test
    public void unassigned_assign_movesBalanceToTargetOnly() {
        long beforeCash = accountDao.recalcBalance(cashId);
        long beforeWechat = accountDao.recalcBalance(wechatId);

        long expenseId1 = insertUnassigned(CategoryEntity.TYPE_EXPENSE, 35_00L);
        long expenseId2 = insertUnassigned(CategoryEntity.TYPE_EXPENSE, 12_50L);
        assertEquals(2, transactionDao.countUnassigned());

        // 归属前：不影响任何账户余额
        assertEquals(beforeCash, accountDao.recalcBalance(cashId));
        assertEquals(beforeWechat, accountDao.recalcBalance(wechatId));

        // 批量归属到现金（DAO 层复现仓库层「UPDATE + 重算」写序）
        long now = 2L;
        int assigned = transactionDao.assignUnassigned(cashId, now);
        assertEquals(2, assigned);
        accountDao.updateBalance(cashId, useCase.calculate(cashId), now);
        accountDao.updateBalance(wechatId, useCase.calculate(wechatId), now);

        // 归属后：目标账户计入两笔支出，另一账户不变，无剩余未归属
        assertEquals(0, transactionDao.countUnassigned());
        assertInvariant(cashId, beforeCash - 35_00L - 12_50L);
        assertInvariant(wechatId, beforeWechat);

        // 归属只补 account_id：行的类型 / 金额原样保留（全局支出统计因此不变）
        assertEquals(Long.valueOf(cashId), transactionDao.getEntityById(expenseId1).accountId);
        assertEquals(35_00L, transactionDao.getEntityById(expenseId1).amount);
        transactionDao.deleteById(expenseId1);
        transactionDao.deleteById(expenseId2);
    }

    // ------------------------------------------------------------------
    // V4.0：退款只在「已到账」时冲减余额（基线第 12、13 章，需真机 / 模拟器）
    // ------------------------------------------------------------------

    /** 直接写退款流水，复现仓库层「改 refund_record → 重算账单累计 → 重算余额」的写序。 */
    private long insertRefund(long transactionId, long amount, String state, long at) {
        RefundRecordEntity refund = new RefundRecordEntity();
        refund.syncId = "refund-" + transactionId + "-" + amount + "-" + state;
        refund.transactionId = transactionId;
        refund.ledgerId = 1L;
        refund.amount = amount;
        refund.state = state;
        refund.requestedAt = at;
        if (RefundRecordEntity.STATE_RECEIVED.equals(state)) {
            refund.receivedAt = Long.valueOf(at);
        }
        refund.createdAt = at;
        refund.updatedAt = at;
        return db.refundRecordDao().insert(refund);
    }

    /** 按仓库层收尾顺序把退款流水落到余额上：重算账单累计列，再重算账户余额缓存。 */
    private void settle(long accountId, long at) {
        assertEquals(1, transactionDao.recomputeRefundTotals());
        accountDao.updateBalance(accountId, useCase.calculate(accountId), at);
    }

    /**
     * 待到账只占额度、已到账才冲减、取消即还原；
     * 同时固定 SQL 投影与 Java 重算两条净额实现恒等，且账单原始金额不被退款改写。
     */
    @Test
    public void refund_reducesBalanceOnlyWhenReceived() {
        long expenseId = insertTransaction(CategoryEntity.TYPE_EXPENSE, 100_00L, cashId, null);
        assertInvariant(cashId, 0L); // 初始 100 元，支出 100 元

        // 1) 待到账 30 元：只占额度，余额一分不动
        long pendingId = insertRefund(expenseId, 30_00L, RefundRecordEntity.STATE_PENDING, 2L);
        settle(cashId, 2L);
        assertInvariant(cashId, 0L);
        assertEquals(0L, transactionDao.getEntityById(expenseId).refundedAmount);
        assertEquals(30_00L, transactionDao.getEntityById(expenseId).pendingRefundAmount);

        // 2) 标记到账：支出净额 100 → 70，余额回升 30 元
        RefundRecordEntity pending = db.refundRecordDao().getById(pendingId);
        pending.state = RefundRecordEntity.STATE_RECEIVED;
        pending.receivedAt = Long.valueOf(3L);
        pending.updatedAt = 3L;
        db.refundRecordDao().update(pending);
        settle(cashId, 3L);
        assertInvariant(cashId, 30_00L);
        assertEquals(30_00L, transactionDao.getEntityById(expenseId).refundedAmount);
        assertEquals(0L, transactionDao.getEntityById(expenseId).pendingRefundAmount);

        // 3) 再退 40 元且直接到账：累计已退 70，净额 30
        long lastId = insertRefund(expenseId, 40_00L, RefundRecordEntity.STATE_RECEIVED, 4L);
        settle(cashId, 4L);
        assertInvariant(cashId, 70_00L);

        // 4) 退款只改两个累计列，账单原始金额仍是 100 元（列表显示原额、统计使用净额）
        TransactionEntity afterRefunds = transactionDao.getEntityById(expenseId);
        assertEquals(100_00L, afterRefunds.amount);
        assertEquals(70_00L, afterRefunds.refundedAmount);
        assertEquals(0L, afterRefunds.pendingRefundAmount);
        assertEquals("SQL 净额聚合必须与余额投影同口径",
                30_00L, transactionDao.sumByTypeAndAccount(CategoryEntity.TYPE_EXPENSE, cashId));

        // 5) 取消那笔 40 元已到账退款：流水保留为 CANCELLED，不再计入累计，余额回落到 30 元
        RefundRecordEntity cancellable = db.refundRecordDao().getById(lastId);
        cancellable.state = RefundRecordEntity.STATE_CANCELLED;
        cancellable.receivedAt = null;
        cancellable.updatedAt = 5L;
        db.refundRecordDao().update(cancellable);
        settle(cashId, 5L);
        assertInvariant(cashId, 30_00L);
        assertEquals(30_00L, transactionDao.getEntityById(expenseId).refundedAmount);
        assertEquals(2, db.refundRecordDao().getByTransaction(expenseId).size());
    }
}
