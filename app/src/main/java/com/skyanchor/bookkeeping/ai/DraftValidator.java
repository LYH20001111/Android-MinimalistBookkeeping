package com.skyanchor.bookkeeping.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.DateUtil;

import java.util.Locale;

/**
 * 草稿校验 / 归一化层（V5 基线第 25、18 章）。
 *
 * <p>AI/OCR 的任何输出在进入确认页之前都必须经过这里：
 * 非法值一律修正为安全默认值，绝不把可疑数据直接放行——
 * 例如负数金额直接清零（交给用户填写），未知类型归为支出。
 * 校验失败不抛错，因为「识别不完整」不是异常而是常态，
 * 缺失字段由确认页引导用户补全。
 */
public final class DraftValidator {

    private DraftValidator() {
    }

    /**
     * 就地修正草稿：类型 / 日期 / 时间 / 置信度全部保证取值合法。
     *
     * @param draft 解析器产出、可能缺字段的草稿
     * @param now   当前时刻（epoch millis），用于补默认日期与时间
     */
    public static void normalize(@NonNull TransactionDraft draft, long now) {
        if (draft.type != CategoryEntity.TYPE_EXPENSE && draft.type != CategoryEntity.TYPE_INCOME) {
            draft.type = CategoryEntity.TYPE_EXPENSE;
        }
        if (draft.amount < 0L || draft.amount > AmountUtil.MAX_CENTS) {
            draft.amount = 0L;
        }
        // 日期给了但落在未来（超过明天）按未识别处理，回落当天。
        long today = DateUtil.startOfDay(now);
        if (draft.date <= 0L || draft.date > DateUtil.addDays(today, 1)) {
            draft.date = today;
        } else {
            draft.date = DateUtil.startOfDay(draft.date);
        }
        draft.time = sanitizeTime(draft.time, now);
        if (draft.categoryId < 0L) {
            draft.categoryId = 0L;
        }
        draft.confidence = clamp(draft.confidence);
        draft.amountConfidence = clamp(draft.amountConfidence);
        draft.categoryConfidence = clamp(draft.categoryConfidence);
    }

    /** 时间文本只接受「HH:mm」；其余（null / 非法 / 越界）回落当前时刻。 */
    @NonNull
    public static String sanitizeTime(@Nullable String time, long now) {
        if (time != null && time.length() >= 4) {
            int hour = DateUtil.hourOfTime(time);
            int minute = DateUtil.minuteOfTime(time);
            if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
                return DateUtil.formatHourMinute(hour, minute);
            }
        }
        return DateUtil.formatHourMinuteOf(now);
    }

    /** 置信度收敛到 0~1000。 */
    public static int clamp(int confidence) {
        return Math.max(0, Math.min(1000, confidence));
    }

    /** 金额字段是否需要用户手动处理（未识别 / 低置信度）。 */
    public static boolean needsAmountAttention(@NonNull TransactionDraft draft) {
        return !draft.hasAmount() || draft.amountConfidence < TransactionDraft.CONFIDENCE_MEDIUM;
    }

    /** 分类是否未可靠匹配（确认页据此高亮「请确认」）。 */
    public static boolean needsCategoryAttention(@NonNull TransactionDraft draft) {
        return draft.categoryId <= 0L
                || draft.categoryConfidence < TransactionDraft.CONFIDENCE_MEDIUM;
    }

    /** 确认页落库前的最终校验：金额必须为正（基线第 25 章）。 */
    public static boolean isSavable(@NonNull TransactionDraft draft) {
        return draft.amount > 0L && draft.date > 0L
                && draft.type != CategoryEntity.TYPE_TRANSFER;
    }

    /**
     * 把商家与备注合并成一条备注文本：商家在前、重复时去重。
     * V5.0 不给账单表加商家列，识别出的商家以备注形式保留（开发计划 §3 决策 3）。
     */
    @Nullable
    public static String mergeMerchantIntoNote(@Nullable String merchant, @Nullable String note) {
        boolean hasMerchant = merchant != null && !merchant.trim().isEmpty();
        boolean hasNote = note != null && !note.trim().isEmpty();
        if (hasMerchant && hasNote) {
            String m = merchant.trim();
            String n = note.trim();
            // 备注里已包含商家名（OCR 常见）就不再重复拼接。
            if (n.contains(m)) {
                return n;
            }
            return m + " " + n;
        }
        if (hasMerchant) {
            return merchant.trim();
        }
        if (hasNote) {
            return note.trim();
        }
        return null;
    }

    /** 「yyyy-MM-dd」格式化，供提示文案与日志。 */
    @NonNull
    public static String formatDate(long dayMillis) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                DateUtil.yearOf(dayMillis), DateUtil.monthOf(dayMillis),
                DateUtil.dayOfMonthOf(dayMillis));
    }
}
