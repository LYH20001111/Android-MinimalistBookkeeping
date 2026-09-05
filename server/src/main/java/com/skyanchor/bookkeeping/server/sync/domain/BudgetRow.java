package com.skyanchor.bookkeeping.server.sync.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** categorySyncId = ""（空串）即总预算哨兵，继承 V2 的 category_id=0 语义。 */
@Entity
@Table(name = "budgets")
public class BudgetRow extends SyncRow {

    @Column(name = "budget_year", nullable = false)
    private int year;

    @Column(name = "budget_month", nullable = false)
    private int month;

    @Column(name = "category_sync_id", nullable = false)
    private String categorySyncId = "";

    @Column(nullable = false)
    private long amount;

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    public int getMonth() {
        return month;
    }

    public void setMonth(int month) {
        this.month = month;
    }

    public String getCategorySyncId() {
        return categorySyncId;
    }

    public void setCategorySyncId(String categorySyncId) {
        this.categorySyncId = categorySyncId;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }
}
