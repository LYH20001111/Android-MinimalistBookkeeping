package com.skyanchor.bookkeeping.domain.refund;

import androidx.annotation.NonNull;

/**
 * 退款规则（V4.0，基线第 12、13 章）。纯函数集合，不依赖 Android 与数据库，供 JVM 单测直接覆盖。
 *
 * <p>冻结口径：仅支出账单可退款；一笔账单可有多笔部分退款；累计退款额（含待到账）不得超过原额；
 * 待到账不冲减统计与余额，已到账才冲减；已取消的退款行保留但不计入任何累计。
 */
public final class RefundPolicy {

    /** 退款展示状态，由「原额 / 已退 / 待退」三个数推导，{@code transactions} 不落状态列。 */
    public enum DisplayState {
        /** 无退款。 */
        NONE,
        /** 部分已到账，无待到账。 */
        PARTIAL_RECEIVED,
        /** 存在待到账（可同时有已到账）。 */
        PENDING,
        /** 已到账累计等于原额。 */
        FULL
    }

    private RefundPolicy() {
    }

    /**
     * 可继续申请退款的额度（分）：原额减去已到账与待到账累计，负值钳位为 0。
     *
     * @param amount          账单原始金额（分）
     * @param refundedAmount  已到账退款累计（分）
     * @param pendingAmount   待到账退款累计（分）
     */
    public static long remainingQuota(long amount, long refundedAmount, long pendingAmount) {
        long quota = amount - refundedAmount - pendingAmount;
        return quota > 0L ? quota : 0L;
    }

    /** 是否还能发起退款：额度为正即可，全额已退后返回 false。 */
    public static boolean canRefund(long amount, long refundedAmount, long pendingAmount) {
        return remainingQuota(amount, refundedAmount, pendingAmount) > 0L;
    }

    /**
     * 一次退款申请是否合法：金额为正且不超过剩余额度。
     *
     * @param refundCents 本次申请退款额（分）
     */
    public static boolean isRequestValid(long refundCents, long amount,
                                         long refundedAmount, long pendingAmount) {
        return refundCents > 0L && refundCents <= remainingQuota(amount, refundedAmount, pendingAmount);
    }

    /**
     * 编辑账单把金额下调后的守卫：新金额不得低于「已到账 + 待到账」退款累计，否则已发生的退款无源可退。
     *
     * @param newAmountCents 编辑后的账单金额（分）
     */
    public static boolean isAmountEditAllowed(long newAmountCents, long refundedAmount, long pendingAmount) {
        return newAmountCents >= refundedAmount + pendingAmount;
    }

    /** 退款展示状态。已到账达到原额即视为全额退款，优先于待到账分支。 */
    @NonNull
    public static DisplayState displayState(long amount, long refundedAmount, long pendingAmount) {
        if (refundedAmount <= 0L && pendingAmount <= 0L) {
            return DisplayState.NONE;
        }
        if (amount > 0L && refundedAmount >= amount) {
            return DisplayState.FULL;
        }
        if (pendingAmount > 0L) {
            return DisplayState.PENDING;
        }
        return DisplayState.PARTIAL_RECEIVED;
    }
}
