package com.skyanchor.bookkeeping.server.sync.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 周期账单规则行：同步规则本身 + 锚点日 + 启用态 + next_run_date（基线第 39 章）。 */
@Entity
@Table(name = "recurring_transactions")
public class RecurringRow extends SyncRow {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int type;

    @Column(nullable = false)
    private long amount;

    @Column(name = "category_sync_id")
    private String categorySyncId;

    @Column(name = "account_sync_id")
    private String accountSyncId;

    @Column(nullable = false)
    private int frequency;

    @Column(name = "repeat_interval", nullable = false)
    private int repeatInterval;

    @Column(name = "start_date", nullable = false)
    private long startDate;

    @Column(name = "end_date", nullable = false)
    private long endDate;

    @Column(name = "next_run_date", nullable = false)
    private long nextRunDate;

    @Column(name = "anchor_day_of_month", nullable = false)
    private int anchorDayOfMonth;

    @Column(name = "is_enabled", nullable = false)
    private boolean isEnabled;

    @Column
    private String note;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public String getCategorySyncId() {
        return categorySyncId;
    }

    public void setCategorySyncId(String categorySyncId) {
        this.categorySyncId = categorySyncId;
    }

    public String getAccountSyncId() {
        return accountSyncId;
    }

    public void setAccountSyncId(String accountSyncId) {
        this.accountSyncId = accountSyncId;
    }

    public int getFrequency() {
        return frequency;
    }

    public void setFrequency(int frequency) {
        this.frequency = frequency;
    }

    public int getRepeatInterval() {
        return repeatInterval;
    }

    public void setRepeatInterval(int repeatInterval) {
        this.repeatInterval = repeatInterval;
    }

    public long getStartDate() {
        return startDate;
    }

    public void setStartDate(long startDate) {
        this.startDate = startDate;
    }

    public long getEndDate() {
        return endDate;
    }

    public void setEndDate(long endDate) {
        this.endDate = endDate;
    }

    public long getNextRunDate() {
        return nextRunDate;
    }

    public void setNextRunDate(long nextRunDate) {
        this.nextRunDate = nextRunDate;
    }

    public int getAnchorDayOfMonth() {
        return anchorDayOfMonth;
    }

    public void setAnchorDayOfMonth(int anchorDayOfMonth) {
        this.anchorDayOfMonth = anchorDayOfMonth;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean isEnabled) {
        this.isEnabled = isEnabled;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
