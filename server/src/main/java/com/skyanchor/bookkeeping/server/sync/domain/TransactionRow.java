package com.skyanchor.bookkeeping.server.sync.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "transactions")
public class TransactionRow extends SyncRow {

    /** 1=支出，2=收入，3=转账。 */
    @Column(nullable = false)
    private int type;

    @Column(nullable = false)
    private long amount;

    /** 业务日期当天 00:00 的 epoch millis。 */
    @Column(nullable = false)
    private long date;

    @Column(nullable = false)
    private String time = "00:00";

    @Column
    private String note;

    @Column(name = "category_sync_id")
    private String categorySyncId;

    @Column(name = "account_sync_id")
    private String accountSyncId;

    @Column(name = "transfer_account_sync_id")
    private String transferAccountSyncId;

    @Column(name = "client_created_at", nullable = false)
    private long clientCreatedAt;

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

    public long getDate() {
        return date;
    }

    public void setDate(long date) {
        this.date = date;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
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

    public String getTransferAccountSyncId() {
        return transferAccountSyncId;
    }

    public void setTransferAccountSyncId(String transferAccountSyncId) {
        this.transferAccountSyncId = transferAccountSyncId;
    }

    public long getClientCreatedAt() {
        return clientCreatedAt;
    }

    public void setClientCreatedAt(long clientCreatedAt) {
        this.clientCreatedAt = clientCreatedAt;
    }
}
