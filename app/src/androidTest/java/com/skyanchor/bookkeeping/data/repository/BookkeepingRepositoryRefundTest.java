package com.skyanchor.bookkeeping.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.skyanchor.bookkeeping.data.database.AppDatabase;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.RefundRecordEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 仓库层退款路径测试（V4.0，需真机 / 模拟器）。
 *
 * <p>与 DAO 层的 {@code AccountBalanceConsistencyTest} 分工不同：这里必须经过
 * {@link BookkeepingRepository}，因为退款的两类风险都只在仓库写序里才暴露——
 * 一是编辑账单走整行 {@code @Update} 时把「已退 / 待退」缓存清零（保存后列表角标与
 * 金额明细凭空消失，下一次退款的额度复核又读到真值而拒绝），
 * 二是修改退款时额度复核若把该笔自身的累计也算进可退上限，就会自己挡自己。
 */
@RunWith(AndroidJUnit4.class)
public class BookkeepingRepositoryRefundTest {

    private static final long EXPENSE_ID_DATE = 1_700_000_000_000L;

    private AppDatabase db;
    private BookkeepingRepository repository;
    private long cashId;
    private long foodCategoryId;
    private long salaryCategoryId;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new BookkeepingRepository(context, db);
        CategoryEntity food = new CategoryEntity("餐饮", "🍜", CategoryEntity.TYPE_EXPENSE, 1,
                false);
        foodCategoryId = db.categoryDao().insert(food);
        CategoryEntity salary = new CategoryEntity("工资", "💰", CategoryEntity.TYPE_INCOME, 1,
                false);
        salaryCategoryId = db.categoryDao().insert(salary);
        cashId = db.accountDao().insert(
                new AccountEntity("现金", AccountEntity.TYPE_CASH, 100_00L, false, 1));
    }

    @After
    public void tearDown() {
        db.close();
    }

    /**
     * 等到仓库的单线程 IO 执行器把前序写入真正落库。
     *
     * <p>测试线程就是主线程，而结果回调经 mainHandler 投递、在此期间不会执行，
     * 因此不能靠回调同步；排入哨兵任务即可确定性地等到写序结束。回调统一传 null。
     */
    private void flushIo() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        repository.getIoExecutor().execute(done::countDown);
        assertTrue("仓库 IO 线程未在 5 秒内完成", done.await(5, TimeUnit.SECONDS));
    }

    /** 走仓库新增一笔账单并返回其本地行 id（saveTransaction 在插入分支回填 id）。 */
    private long insertTransaction(int type, long amount) throws InterruptedException {
        TransactionEntity entity = new TransactionEntity();
        entity.type = type;
        entity.amount = amount;
        entity.categoryId = type == CategoryEntity.TYPE_TRANSFER
                ? null : Long.valueOf(type == CategoryEntity.TYPE_INCOME
                        ? salaryCategoryId : foodCategoryId);
        entity.accountId = Long.valueOf(cashId);
        entity.transferAccountId = null;
        entity.date = EXPENSE_ID_DATE;
        entity.time = "12:00";
        repository.saveTransaction(entity, null);
        flushIo();
        assertTrue("账单未落库", entity.id != 0L);
        return entity.id;
    }

    /** 复现编辑页的保存：只带表单字段的「半实体」，同步与退款元数据全为默认值。 */
    private void saveFromEditForm(long id, long amount, String note) throws InterruptedException {
        TransactionEntity entity = new TransactionEntity();
        entity.id = id;
        entity.type = CategoryEntity.TYPE_EXPENSE;
        entity.amount = amount;
        entity.categoryId = Long.valueOf(foodCategoryId);
        entity.accountId = Long.valueOf(cashId);
        entity.date = EXPENSE_ID_DATE;
        entity.time = "12:00";
        entity.note = note;
        repository.saveTransaction(entity, null);
        flushIo();
    }

    private long onlyRefundId(long transactionId) {
        assertEquals(1, db.refundRecordDao().getByTransaction(transactionId).size());
        return db.refundRecordDao().getByTransaction(transactionId).get(0).id;
    }

    /** 按金额定位退款流水，避免依赖 DAO 的返回顺序。 */
    private RefundRecordEntity refundWithAmount(long transactionId, long amount) {
        for (RefundRecordEntity refund : db.refundRecordDao().getByTransaction(transactionId)) {
            if (refund.amount == amount) {
                return refund;
            }
        }
        throw new AssertionError("找不到金额 " + amount + " 的退款流水");
    }

    /**
     * 带退款的账单被编辑页保存后，两个累计列与净额余额都不能被清零。
     *
     * <p>这是回归锁：若整行覆写再次打穿缓存，余额会从 30 元掉回 0 元（按未退款全额计入支出）。
     */
    @Test
    public void saveTransaction_fromEditFormKeepsRefundCachesAndNetBalance()
            throws InterruptedException {
        long expenseId = insertTransaction(CategoryEntity.TYPE_EXPENSE, 100_00L);
        assertEquals(0L, db.accountDao().getById(cashId).balance);

        repository.requestRefund(expenseId, 30_00L, "部分退货", true, null);
        flushIo();
        assertEquals(30_00L, db.transactionDao().getEntityById(expenseId).refundedAmount);
        assertEquals(30_00L, db.accountDao().getById(cashId).balance);

        saveFromEditForm(expenseId, 100_00L, "改了备注");

        TransactionEntity afterSave = db.transactionDao().getEntityById(expenseId);
        assertEquals("已退款缓存被整行覆写清零了", 30_00L, afterSave.refundedAmount);
        assertEquals(0L, afterSave.pendingRefundAmount);
        assertEquals("改了备注", afterSave.note);
        assertEquals("净额余额必须仍按扣除退款后的 30 元计",
                30_00L, db.accountDao().getById(cashId).balance);
        assertEquals("退款流水仍只有那一条", 1,
                db.refundRecordDao().getByTransaction(expenseId).size());
    }

    /** 只有支出可退款：收入账单发起退款不产生流水，也不碰累计列。 */
    @Test
    public void requestRefund_rejectsIncomeTransaction() throws InterruptedException {
        long incomeId = insertTransaction(CategoryEntity.TYPE_INCOME, 50_00L);
        repository.requestRefund(incomeId, 10_00L, null, true, null);
        flushIo();
        assertTrue(db.refundRecordDao().getByTransaction(incomeId).isEmpty());
        assertEquals(0L, db.transactionDao().getEntityById(incomeId).refundedAmount);
    }

    /** 额度复核要排除该笔自身：上调到自己原来的额度必须放行，越过其他退款累计才拒绝。 */
    @Test
    public void updateRefund_revalidatesQuotaExcludingItself() throws InterruptedException {
        long expenseId = insertTransaction(CategoryEntity.TYPE_EXPENSE, 100_00L);
        repository.requestRefund(expenseId, 30_00L, null, false, null);
        repository.requestRefund(expenseId, 20_00L, null, false, null);
        flushIo();
        assertEquals(50_00L, db.transactionDao().getEntityById(expenseId).pendingRefundAmount);

        RefundRecordEntity target = refundWithAmount(expenseId, 30_00L);
        // 另一条待到账占 20 元，把这条 30 元改成 80 元仍在原额之内，必须受理。
        repository.updateRefund(target.id, 80_00L, null, false, null);
        flushIo();
        assertEquals(100_00L, db.transactionDao().getEntityById(expenseId).pendingRefundAmount);

        // 再改成 90 元就越过「原额 − 其他累计 20 元」的上限，必须整体不生效。
        repository.updateRefund(target.id, 90_00L, null, false, null);
        flushIo();
        assertEquals(80_00L, db.refundRecordDao().getById(target.id).amount);
        assertEquals(100_00L, db.transactionDao().getEntityById(expenseId).pendingRefundAmount);
        // 全是待到账，一分钱都不该冲减余额（初始 100 元 − 支出 100 元）。
        assertEquals(0L, db.accountDao().getById(cashId).balance);
    }

    /** 已到账改回待到账：累计列回到待退，余额冲减随之撤销，到账时间清空。 */
    @Test
    public void updateRefund_receivedBackToPendingRestoresBalance() throws InterruptedException {
        long expenseId = insertTransaction(CategoryEntity.TYPE_EXPENSE, 100_00L);
        repository.requestRefund(expenseId, 30_00L, "整单退", true, null);
        flushIo();
        long refundId = onlyRefundId(expenseId);
        assertEquals(30_00L, db.accountDao().getById(cashId).balance);
        assertNotNull(db.refundRecordDao().getById(refundId).receivedAt);

        repository.updateRefund(refundId, 30_00L, "商家说先不退", false, null);
        flushIo();

        RefundRecordEntity pending = db.refundRecordDao().getById(refundId);
        assertEquals(RefundRecordEntity.STATE_PENDING, pending.state);
        assertNull("改回待到账必须清空到账时间", pending.receivedAt);
        assertEquals("商家说先不退", pending.reason);
        TransactionEntity tx = db.transactionDao().getEntityById(expenseId);
        assertEquals(0L, tx.refundedAmount);
        assertEquals(30_00L, tx.pendingRefundAmount);
        assertEquals("待到账不冲减余额", 0L, db.accountDao().getById(cashId).balance);
    }
}
