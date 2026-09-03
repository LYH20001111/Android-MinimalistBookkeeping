package com.skyanchor.bookkeeping.domain.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 账户余额计算单元测试（V2 关键数据模型决策 #3）。
 *
 * <p>余额统一"可正可负"模型：{@code balance = initial_balance + 收入 - 支出 + 转入 - 转出}。
 * 信用卡欠款即负余额，不单独设计信用账务。
 *
 * <p>{@link CalculateAccountBalanceUseCase#compute} 是与框架无关的纯函数，这里直接验证它，
 * 与 {@code AccountDao} 的 SQL 投影互为独立实现、彼此校验（Phase 9 一致性验证）。
 */
public class AccountBalanceCalculatorTest {

    /** 只有初始余额、没有任何交易时，余额即初始余额。 */
    @Test
    public void compute_returnsInitialBalanceWithoutAnyFlow() {
        assertEquals(50000L, CalculateAccountBalanceUseCase.compute(50000L, 0L, 0L, 0L, 0L));
    }

    /** 收入增加余额。 */
    @Test
    public void compute_addsIncome() {
        // 初始 100.00 元 + 收入 250.00 元 = 350.00 元
        assertEquals(35000L, CalculateAccountBalanceUseCase.compute(10000L, 25000L, 0L, 0L, 0L));
    }

    /** 支出减少余额。 */
    @Test
    public void compute_subtractsExpense() {
        // 初始 100.00 元 - 支出 30.00 元 = 70.00 元
        assertEquals(7000L, CalculateAccountBalanceUseCase.compute(10000L, 0L, 3000L, 0L, 0L));
    }

    /** 转入增加余额（本账户是转账的收款方）。 */
    @Test
    public void compute_addsTransferIn() {
        // 初始 0 + 转入 88.88 元 = 88.88 元
        assertEquals(8888L, CalculateAccountBalanceUseCase.compute(0L, 0L, 0L, 8888L, 0L));
    }

    /** 转出减少余额（本账户是转账的付款方）。 */
    @Test
    public void compute_subtractsTransferOut() {
        // 初始 200.00 元 - 转出 50.00 元 = 150.00 元
        assertEquals(15000L, CalculateAccountBalanceUseCase.compute(20000L, 0L, 0L, 0L, 5000L));
    }

    /** 四类资金流同时存在时，严格按公式合并。 */
    @Test
    public void compute_combinesAllFourFlows() {
        // 1000 + 200(收入) - 300(支出) + 400(转入) - 500(转出) = 800
        long balance = CalculateAccountBalanceUseCase.compute(100000L, 20000L, 30000L, 40000L, 50000L);
        assertEquals(80000L, balance);
    }

    /**
     * 信用卡负余额：初始 0，只刷卡消费不还款，余额为负即欠款。
     * 这是"可正可负"模型覆盖信用卡场景的关键断言。
     */
    @Test
    public void compute_creditCardGoesNegativeOnSpending() {
        // 信用卡初始 0，支出 1234.56 元 → 欠款 -1234.56 元
        long balance = CalculateAccountBalanceUseCase.compute(0L, 0L, 123456L, 0L, 0L);
        assertEquals(-123456L, balance);
        assertTrue("信用卡消费后应为负余额", balance < 0L);
    }

    /** 支出 + 转出超过初始余额与收入之和时，允许出现负余额（不夹到 0）。 */
    @Test
    public void compute_allowsNegativeBalance() {
        // 100 + 50(收入) - 200(支出) - 100(转出) = -150
        long balance = CalculateAccountBalanceUseCase.compute(10000L, 5000L, 20000L, 0L, 10000L);
        assertEquals(-15000L, balance);
    }

    /** 还款（转入信用卡）可把负余额拉回。 */
    @Test
    public void compute_repaymentRecoversNegativeBalance() {
        // 信用卡先欠 500.00 元，再还款（转入）500.00 元 → 归零
        long balance = CalculateAccountBalanceUseCase.compute(0L, 0L, 50000L, 50000L, 0L);
        assertEquals(0L, balance);
    }

    /** 全部为 0 时余额为 0，不抛异常。 */
    @Test
    public void compute_zeroEverything() {
        assertEquals(0L, CalculateAccountBalanceUseCase.compute(0L, 0L, 0L, 0L, 0L));
    }
}
