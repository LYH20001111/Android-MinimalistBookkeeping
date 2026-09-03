package com.skyanchor.bookkeeping.util;

import android.content.Context;
import android.content.res.Resources;

import androidx.annotation.NonNull;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.model.DateRange;

/**
 * 日期文案工厂。把 {@link DateUtil} 产出的纯数字结果翻译成 string 资源里的中文文案，
 * 保证「布局与代码中不出现硬编码字符串」（V1 基线第 14 章）。
 */
public final class DateLabels {

    private DateLabels() {
    }

    /**
     * 生成相对参考日的分组标题：今天 / 昨天 / 9月2日 / 2025年12月3日。
     *
     * @param referenceDay 参考日（记录页传所选业务日期）
     */
    @NonNull
    public static DayLabelProvider dayLabels(@NonNull Context context, long referenceDay) {
        final Resources resources = context.getResources();
        final long reference = DateUtil.startOfDay(referenceDay);
        return dayMillis -> {
            switch (DateUtil.dayHeaderKind(dayMillis, reference)) {
                case DateUtil.DAY_TODAY:
                    return resources.getString(R.string.day_label_today);
                case DateUtil.DAY_YESTERDAY:
                    return resources.getString(R.string.day_label_yesterday);
                case DateUtil.DAY_THIS_YEAR:
                    return resources.getString(R.string.date_format_month_day,
                            DateUtil.monthOf(dayMillis), DateUtil.dayOfMonthOf(dayMillis));
                case DateUtil.DAY_OTHER_YEAR:
                default:
                    return resources.getString(R.string.date_format_full,
                            DateUtil.yearOf(dayMillis), DateUtil.monthOf(dayMillis),
                            DateUtil.dayOfMonthOf(dayMillis));
            }
        };
    }

    /**
     * 业务日期按钮的文案，以「今天」为参考日：选中今天时显示「今天」，
     * 选中其他日期时显示具体日期。
     */
    @NonNull
    public static String businessDateLabel(@NonNull Context context, long dayMillis) {
        return dayLabels(context, DateUtil.today()).label(dayMillis);
    }

    /**
     * 表单里的绝对日期文案：同年显示「9月2日」，跨年显示「2025年12月3日」。
     *
     * <p>与 {@link #businessDateLabel} 的区别是这里不输出「今天 / 昨天」，
     * 因为新增/编辑页的日期字段需要一个不随当前时刻漂移的确定值。
     */
    @NonNull
    public static String fullDayLabel(@NonNull Context context, long dayMillis) {
        Resources resources = context.getResources();
        if (DateUtil.yearOf(dayMillis) == DateUtil.yearOf(DateUtil.today())) {
            return resources.getString(R.string.date_format_month_day,
                    DateUtil.monthOf(dayMillis), DateUtil.dayOfMonthOf(dayMillis));
        }
        return resources.getString(R.string.date_format_full,
                DateUtil.yearOf(dayMillis), DateUtil.monthOf(dayMillis),
                DateUtil.dayOfMonthOf(dayMillis));
    }

    /**
     * 周期标题：周显示「2026年第5周」（V2 Risk A：含年份消除跨年周序号歧义），
     * 月显示「2024年5月」，年显示「2024年」。
     */
    @NonNull
    public static String periodTitle(@NonNull Context context, @NonNull DateRange range) {
        Resources resources = context.getResources();
        switch (range.type) {
            case WEEK:
                // 年份取周一所在年，与 weekOfYear 的 ISO 口径一致；周期身份仍用 start/end。
                return resources.getString(R.string.date_format_week_label,
                        DateUtil.yearOf(range.start), DateUtil.weekOfYear(range.start));
            case YEAR:
                return resources.getString(R.string.date_format_year, range.year);
            case MONTH:
            default:
                return resources.getString(R.string.date_format_year_month, range.year, range.month);
        }
    }

    /**
     * 周期副标题：把周期区间压缩成紧凑的日期范围「MM.dd-MM.dd」。
     *
     * <p>周显示实际起止（可能跨月），月显示当月首末日，年固定为 01.01-12.31。
     * 与 {@link #periodTitle} 搭配使用，标题定位周期、副标题给出精确边界。
     */
    @NonNull
    public static String periodSubtitle(@NonNull Context context, @NonNull DateRange range) {
        return context.getResources().getString(R.string.date_format_range_dot,
                DateUtil.monthOf(range.start), DateUtil.dayOfMonthOf(range.start),
                DateUtil.monthOf(range.end), DateUtil.dayOfMonthOf(range.end));
    }

    /** 月份导航标题，用于预算设置页：「2024年5月」。 */
    @NonNull
    public static String monthTitle(@NonNull Context context, int year, int month) {
        return context.getResources().getString(R.string.date_format_year_month, year, month);
    }
}
