package com.skyanchor.bookkeeping.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;

/**
 * CSV 导出专用投影（V2 新增，开发计划 Phase 5）。
 *
 * <p>与 {@link TransactionItem} 的区别：额外携带 {@code createdAt / updatedAt} 两个时间戳，
 * 因为导出列需要「创建时间 / 更新时间」，而列表投影为了轻量并不包含它们。导出走一次性同步查询
 * （{@code TransactionDao.exportAll()}），不进 LiveData，故与列表刷新互不干扰。
 *
 * <p>金额单位仍是「分」，格式化到「元」由 {@code CsvFormatter} 负责；日期是本地当天 00:00 的
 * epoch millis，格式化到 {@code yyyy-MM-dd} 同样在 {@code CsvFormatter} 内完成。
 */
public class TransactionExport {

    @ColumnInfo(name = "id")
    public long id;

    /** 1=支出，2=收入，3=转账。 */
    @ColumnInfo(name = "type")
    public int type;

    /** 金额，单位：分。 */
    @ColumnInfo(name = "amount")
    public long amount;

    /** 分类 id；转账经 COALESCE 归 0。 */
    @ColumnInfo(name = "categoryId")
    public long categoryId;

    @Nullable
    @ColumnInfo(name = "categoryName")
    public String categoryName;

    /** 账户 id：支出=付款、收入=收款、转账=转出；历史账单为 null。 */
    @Nullable
    @ColumnInfo(name = "accountId")
    public Long accountId;

    @Nullable
    @ColumnInfo(name = "accountName")
    public String accountName;

    /** 转入账户 id，仅转账非空。 */
    @Nullable
    @ColumnInfo(name = "transferAccountId")
    public Long transferAccountId;

    @Nullable
    @ColumnInfo(name = "transferAccountName")
    public String transferAccountName;

    /** 业务日期，当天 00:00 的 epoch millis。 */
    @ColumnInfo(name = "date")
    public long date;

    @NonNull
    @ColumnInfo(name = "time")
    public String time = "";

    @Nullable
    @ColumnInfo(name = "note")
    public String note;

    @ColumnInfo(name = "createdAt")
    public long createdAt;

    @ColumnInfo(name = "updatedAt")
    public long updatedAt;

    @NonNull
    public String displayCategoryName() {
        return categoryName == null ? "" : categoryName;
    }

    @NonNull
    public String displayAccountName() {
        return accountName == null ? "" : accountName;
    }

    @NonNull
    public String displayTransferAccountName() {
        return transferAccountName == null ? "" : transferAccountName;
    }

    @NonNull
    public String displayNote() {
        return note == null ? "" : note;
    }
}
