package com.skyanchor.bookkeeping.ui.record;

import com.skyanchor.bookkeeping.data.model.PeriodSummary;
import com.skyanchor.bookkeeping.data.model.RecordListItem;

import java.util.Collections;
import java.util.List;

/**
 * 记录页的一次性渲染快照。
 *
 * <p>把「业务日期、当天概览、分组后的账单列表」合并成一个不可变对象，
 * 界面只做一次判空与赋值，避免出现列表与概览来自不同时刻的数据。
 */
public final class RecordUiState {

    /** 当前业务日期，当天 00:00 的 epoch millis。 */
    public final long businessDate;

    /** 业务日期按钮文案，例如「今天」「9月2日」。 */
    public final String businessDateLabel;

    /** 所选业务日期当天的收支概览。 */
    public final PeriodSummary daySummary;

    /** 日期不晚于业务日期的全部账单，已按天分组。 */
    public final List<RecordListItem> rows;

    public RecordUiState(long businessDate, String businessDateLabel, PeriodSummary daySummary,
                         List<RecordListItem> rows) {
        this.businessDate = businessDate;
        this.businessDateLabel = businessDateLabel;
        this.daySummary = daySummary == null ? PeriodSummary.EMPTY : daySummary;
        this.rows = rows == null ? Collections.<RecordListItem>emptyList() : rows;
    }

    /** 没有任何账单时展示引导，而不是空白列表。 */
    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
