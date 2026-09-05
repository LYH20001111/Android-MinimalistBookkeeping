package com.skyanchor.bookkeeping.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 指数退避策略单测（基线第 10.1 章）：
 * 5s → 15s → 30s → 1min → 2min → 4min → … → 封顶 30 分钟。
 */
public class RetryPolicyTest {

    @Test
    public void firstFailure_waitsFiveSeconds() {
        assertEquals(5_000L, RetryPolicy.delayFor(1));
    }

    @Test
    public void earlySequence_matchesBaseline() {
        assertEquals(15_000L, RetryPolicy.delayFor(2));
        assertEquals(30_000L, RetryPolicy.delayFor(3));
        assertEquals(60_000L, RetryPolicy.delayFor(4));
    }

    @Test
    public void laterFailures_doubleUntilCap() {
        assertEquals(120_000L, RetryPolicy.delayFor(5));
        assertEquals(240_000L, RetryPolicy.delayFor(6));
        assertEquals(480_000L, RetryPolicy.delayFor(7));
        assertEquals(960_000L, RetryPolicy.delayFor(8));
    }

    @Test
    public void longFailureSeries_capsAtThirtyMinutes() {
        // 1min ×2^k 封顶 30min：第 10 次失败起恒为 30 分钟
        assertEquals(RetryPolicy.MAX_DELAY_MS, RetryPolicy.delayFor(10));
        assertEquals(RetryPolicy.MAX_DELAY_MS, RetryPolicy.delayFor(11));
        assertEquals(30L * 60 * 1000, RetryPolicy.MAX_DELAY_MS);
    }

    @Test
    public void zeroOrNegative_fallsBackToFirstDelay() {
        assertEquals(5_000L, RetryPolicy.delayFor(0));
        assertEquals(5_000L, RetryPolicy.delayFor(-3));
    }

    @Test
    public void delays_areMonotonicallyNonDecreasing() {
        long previous = 0;
        for (int i = 1; i <= 24; i++) {
            long delay = RetryPolicy.delayFor(i);
            assertTrue("delay must not decrease at " + i, delay >= previous);
            assertTrue("delay must not exceed cap at " + i, delay <= RetryPolicy.MAX_DELAY_MS);
            previous = delay;
        }
    }
}
