package com.skyanchor.bookkeeping.data.model;

import com.skyanchor.bookkeeping.data.entity.TransactionItem;

/**
 * 记录页列表的一行。列表由「日期分组标题」与「账单行」两种类型交替组成。
 */
public abstract class RecordListItem {

    public static final int VIEW_TYPE_HEADER = 0;
    public static final int VIEW_TYPE_TRANSACTION = 1;

    public abstract int viewType();

    /** 日期分组标题：显示相对业务日期的标签与当日合计。 */
    public static final class Header extends RecordListItem {

        /** 当天 00:00 的 epoch millis。 */
        public final long dayMillis;

        /**
         * 分组标题文案，例如「今天」「9月2日」。
         * 由 {@code DayLabelProvider} 结合参考日生成，随业务日期变化而变化，
         * 因此必须参与 equals，否则切换业务日期后 DiffUtil 不会重绘标题。
         */
        public final String label;

        /** 当日支出合计，单位：分。 */
        public final long expense;

        /** 当日收入合计，单位：分。 */
        public final long income;

        /** 当日账单笔数。 */
        public final int count;

        public Header(long dayMillis, String label, long expense, long income, int count) {
            this.dayMillis = dayMillis;
            this.label = label;
            this.expense = expense;
            this.income = income;
            this.count = count;
        }

        @Override
        public int viewType() {
            return VIEW_TYPE_HEADER;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Header)) {
                return false;
            }
            Header other = (Header) o;
            return dayMillis == other.dayMillis && expense == other.expense
                    && income == other.income && count == other.count
                    && (label == null ? other.label == null : label.equals(other.label));
        }

        @Override
        public int hashCode() {
            int result = (int) (dayMillis ^ (dayMillis >>> 32));
            result = 31 * result + (label == null ? 0 : label.hashCode());
            result = 31 * result + count;
            return result;
        }
    }

    /** 单笔账单行。 */
    public static final class Row extends RecordListItem {

        public final TransactionItem item;

        public Row(TransactionItem item) {
            this.item = item;
        }

        @Override
        public int viewType() {
            return VIEW_TYPE_TRANSACTION;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Row)) {
                return false;
            }
            Row other = (Row) o;
            return item == null ? other.item == null : item.equals(other.item);
        }

        @Override
        public int hashCode() {
            return item == null ? 0 : item.hashCode();
        }
    }
}
