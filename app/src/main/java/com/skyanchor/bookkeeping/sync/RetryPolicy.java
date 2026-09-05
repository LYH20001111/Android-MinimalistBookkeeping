package com.skyanchor.bookkeeping.sync;

/**
 * 指数退避策略（基线第 10.1 章，纯函数、JVM 可测）：
 * 5s → 15s → 30s → 1min → 后续逐步增加，封顶 30 分钟。
 */
public final class RetryPolicy {

    public static final long MAX_DELAY_MS = 30L * 60 * 1000;

    private static final long[] EARLY_SEQUENCE = {
            5_000L, 15_000L, 30_000L, 60_000L
    };

    private RetryPolicy() {
    }

    /**
     * @param failedRetryCount 已连续失败次数（第 1 次失败传 1）
     * @return 距下次重试的毫秒数
     */
    public static long delayFor(int failedRetryCount) {
        if (failedRetryCount <= 0) {
            return EARLY_SEQUENCE[0];
        }
        if (failedRetryCount <= EARLY_SEQUENCE.length) {
            return EARLY_SEQUENCE[failedRetryCount - 1];
        }
        // 之后每多失败一次 ×2：2min、4min、8min、16min、30min…封顶 30min
        long delay = EARLY_SEQUENCE[EARLY_SEQUENCE.length - 1];
        for (int i = EARLY_SEQUENCE.length; i < failedRetryCount; i++) {
            delay *= 2;
            if (delay >= MAX_DELAY_MS) {
                return MAX_DELAY_MS;
            }
        }
        return Math.min(delay, MAX_DELAY_MS);
    }
}
