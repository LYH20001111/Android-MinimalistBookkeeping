package com.skyanchor.bookkeeping.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.util.Objects;

/**
 * 周期账单模板（V2 新增，schema 本轮建好，生成逻辑见 Phase 8）。
 *
 * <p>到期规则：{@code next_run_date <= today 且 is_enabled} 的模板生成「待确认」occurrence，
 * 由用户一键确认后写入交易并幂等推进 {@code next_run_date}；不做后台静默创建。
 *
 * <p>频率推进：日按 +interval 天，周按 weekday；月 / 年自 V2.1 起改为「原始锚点日」重推
 * （{@code anchor_day_of_month}，如 1 月 31 日 → 2 月 28 日 → 3 月 31 日），不再从上一次
 * 被夹取的日期继续推导，消除月末日期漂移。金额单位为分；{@code end_date = 0} 表示无结束日期。
 */
@Entity(
        tableName = "recurring_transaction",
        indices = {
                @Index(value = "next_run_date"),
                @Index(value = "is_enabled")
        })
public class RecurringTransactionEntity {

    /** 每天。 */
    public static final int FREQUENCY_DAILY = 1;
    /** 每周。 */
    public static final int FREQUENCY_WEEKLY = 2;
    /** 每月。 */
    public static final int FREQUENCY_MONTHLY = 3;
    /** 每年。 */
    public static final int FREQUENCY_YEARLY = 4;

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "name")
    public String name = "";

    /** 1=支出，2=收入（周期账单不含转账）。 */
    @ColumnInfo(name = "type")
    public int type = CategoryEntity.TYPE_EXPENSE;

    /** 金额，单位：分。 */
    @ColumnInfo(name = "amount")
    public long amount;

    /** 分类 id，可空。 */
    @Nullable
    @ColumnInfo(name = "category_id")
    public Long categoryId;

    /** 账户 id，可空。 */
    @Nullable
    @ColumnInfo(name = "account_id")
    public Long accountId;

    /** 频率，取值见 FREQUENCY_* 常量。 */
    @ColumnInfo(name = "frequency")
    public int frequency = FREQUENCY_MONTHLY;

    /** 间隔，默认 1（每 1 个频率单位一次）。 */
    @ColumnInfo(name = "repeat_interval")
    public int interval = 1;

    /** 起始日期，当天 00:00 的 epoch millis。 */
    @ColumnInfo(name = "start_date")
    public long startDate;

    /** 结束日期，当天 00:00 的 epoch millis；0 表示无结束日期。 */
    @ColumnInfo(name = "end_date")
    public long endDate;

    /** 下次应记账日期，当天 00:00 的 epoch millis。 */
    @ColumnInfo(name = "next_run_date")
    public long nextRunDate;

    /**
     * V2.1：月 / 年周期的原始锚点日（1–31）。每次推进都从它重新推导目标月的天数
     * （如 31 → 2 月取 28/29），不从上一次被夹取的日期继续推导；日 / 周频率不使用。
     */
    @ColumnInfo(name = "anchor_day_of_month")
    public int anchorDayOfMonth;

    @ColumnInfo(name = "is_enabled")
    public boolean isEnabled = true;

    @Nullable
    @ColumnInfo(name = "note")
    public String note;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RecurringTransactionEntity)) {
            return false;
        }
        RecurringTransactionEntity other = (RecurringTransactionEntity) o;
        return id == other.id
                && type == other.type
                && amount == other.amount
                && frequency == other.frequency
                && interval == other.interval
                && anchorDayOfMonth == other.anchorDayOfMonth
                && startDate == other.startDate
                && endDate == other.endDate
                && nextRunDate == other.nextRunDate
                && isEnabled == other.isEnabled
                && Objects.equals(name, other.name)
                && Objects.equals(categoryId, other.categoryId)
                && Objects.equals(accountId, other.accountId)
                && Objects.equals(note, other.note);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, type, amount, categoryId, accountId, frequency, interval,
                anchorDayOfMonth, startDate, endDate, nextRunDate, isEnabled, note);
    }
}
