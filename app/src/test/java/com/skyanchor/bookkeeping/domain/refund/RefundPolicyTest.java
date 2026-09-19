package com.skyanchor.bookkeeping.domain.refund;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.skyanchor.bookkeeping.domain.refund.RefundPolicy.DisplayState;

import org.junit.Test;

/**
 * 退款规则单测（V4.0）。冻结口径：仅支出可退款、可多次部分退款、
 * 累计（含待到账）不得超过原额、待到账不冲减统计与余额、取消行不计入任何累计。
 */
public class RefundPolicyTest {

    @Test
    public void remainingQuota_pendingAlsoConsumesQuota() {
        // 原额 100.00，已退 30.00，待退 20.00 → 还能退 50.00
        assertEquals(5000L, RefundPolicy.remainingQuota(10000L, 3000L, 2000L));
        // 待到账同样占额度，不能因为它还没冲减余额就重复退
        assertEquals(0L, RefundPolicy.remainingQuota(10000L, 0L, 10000L));
    }

    @Test
    public void remainingQuota_neverNegative() {
        // 多设备并发可能让累计超过原额，额度钳位为 0 而不是负数
        assertEquals(0L, RefundPolicy.remainingQuota(10000L, 8000L, 5000L));
        assertEquals(0L, RefundPolicy.remainingQuota(10000L, 25000L, 0L));
    }

    @Test
    public void canRefund_falseOnlyWhenExhausted() {
        assertTrue(RefundPolicy.canRefund(10000L, 9999L, 0L));
        assertFalse("已到账凑满原额即不可再退", RefundPolicy.canRefund(10000L, 10000L, 0L));
        assertFalse("待到账占满额度同样不可退", RefundPolicy.canRefund(10000L, 0L, 10000L));
        assertFalse(RefundPolicy.canRefund(10000L, 10000L, 1L));
    }

    @Test
    public void isRequestValid_rejectsNonPositiveAndOverQuota() {
        assertFalse("金额必须为正", RefundPolicy.isRequestValid(0L, 10000L, 0L, 0L));
        assertFalse(RefundPolicy.isRequestValid(-100L, 10000L, 0L, 0L));
        assertFalse("超出剩余额度", RefundPolicy.isRequestValid(5001L, 10000L, 3000L, 2000L));
        assertTrue("恰好退到上限", RefundPolicy.isRequestValid(5000L, 10000L, 3000L, 2000L));
        assertTrue(RefundPolicy.isRequestValid(1L, 10000L, 9999L, 0L));
        assertFalse("已退完则一分钱也不能再退", RefundPolicy.isRequestValid(1L, 10000L, 10000L, 0L));
    }

    @Test
    public void isAmountEditAllowed_newAmountMustCoverRefunds() {
        // 下调后的金额不得低于「已退 + 待退」，否则已发生的退款无源可退
        assertTrue(RefundPolicy.isAmountEditAllowed(5000L, 3000L, 2000L));
        assertFalse(RefundPolicy.isAmountEditAllowed(4999L, 3000L, 2000L));
        assertTrue("无退款时任意正金额都能改", RefundPolicy.isAmountEditAllowed(1L, 0L, 0L));
        assertTrue("上调总是允许", RefundPolicy.isAmountEditAllowed(99999L, 3000L, 2000L));
    }

    @Test
    public void displayState_mapsFrozenMatrix() {
        assertEquals(DisplayState.NONE, RefundPolicy.displayState(10000L, 0L, 0L));
        assertEquals(DisplayState.PARTIAL_RECEIVED, RefundPolicy.displayState(10000L, 3000L, 0L));
        assertEquals(DisplayState.PENDING, RefundPolicy.displayState(10000L, 3000L, 2000L));
        assertEquals("只登记了待到账", DisplayState.PENDING,
                RefundPolicy.displayState(10000L, 0L, 2000L));
    }

    @Test
    public void displayState_fullTakesPriorityOverPending() {
        // 已到账凑满原额后即使仍挂着待到账行（并发/异常路径），展示仍以全额退款为准
        assertEquals(DisplayState.FULL, RefundPolicy.displayState(10000L, 10000L, 2000L));
        assertEquals(DisplayState.FULL, RefundPolicy.displayState(10000L, 12000L, 0L));
    }

    @Test
    public void displayState_zeroAmountBillIsNotFullRefund() {
        // 原额为 0 时不进入 FULL 分支，避免 0 额账单显示成「已全额退款」
        assertEquals(DisplayState.PARTIAL_RECEIVED, RefundPolicy.displayState(0L, 0L, 1L));
        assertEquals(DisplayState.NONE, RefundPolicy.displayState(0L, 0L, 0L));
    }
}
