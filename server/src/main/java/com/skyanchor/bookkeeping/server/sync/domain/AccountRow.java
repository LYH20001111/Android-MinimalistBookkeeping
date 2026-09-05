package com.skyanchor.bookkeeping.server.sync.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** balance 仅存客户端上报的缓存，不参与任何裁决；真值由客户端按交易重算。 */
@Entity
@Table(name = "accounts")
public class AccountRow extends SyncRow {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int type;

    @Column(name = "initial_balance", nullable = false)
    private long initialBalance;

    @Column(nullable = false)
    private long balance;

    @Column(name = "is_credit", nullable = false)
    private boolean isCredit;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_archived", nullable = false)
    private boolean isArchived;

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

    public long getInitialBalance() {
        return initialBalance;
    }

    public void setInitialBalance(long initialBalance) {
        this.initialBalance = initialBalance;
    }

    public long getBalance() {
        return balance;
    }

    public void setBalance(long balance) {
        this.balance = balance;
    }

    public boolean isCredit() {
        return isCredit;
    }

    public void setCredit(boolean isCredit) {
        this.isCredit = isCredit;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isArchived() {
        return isArchived;
    }

    public void setArchived(boolean isArchived) {
        this.isArchived = isArchived;
    }
}
