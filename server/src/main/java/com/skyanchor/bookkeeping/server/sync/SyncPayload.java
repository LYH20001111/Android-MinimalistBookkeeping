package com.skyanchor.bookkeeping.server.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 同步协议统一载荷（Sync Protocol Version 1）。
 *
 * <p>五个实体共用一个超集 DTO，按 entityType 取用对应字段，其余为 null——
 * 协议向前加字段不破坏双方解析。引用字段一律是对方的 syncId（跨设备稳定身份），
 * 由客户端与本地自增 id 互转；预算的总预算哨兵在协议层是 categorySyncId=null/""。
 * 金额单位「分」（long），时间一律 epoch millis（long）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SyncPayload {

    // ===== Category =====
    public String name;
    public String icon;
    public Integer type;
    public Integer sortOrder;
    public Boolean isDefault;
    // ===== Account =====
    public Long initialBalance;
    public Long balance;
    public Boolean isCredit;
    public Boolean isArchived;
    // ===== Transaction / Recurring =====
    public Long amount;
    public String categorySyncId;
    public String accountSyncId;
    public String transferAccountSyncId;
    public Long date;
    public String time;
    public String note;
    public Long clientCreatedAt;
    // ===== Budget =====
    public Integer year;
    public Integer month;
    // ===== Recurring =====
    public Integer frequency;
    public Integer repeatInterval;
    public Long startDate;
    public Long endDate;
    public Long nextRunDate;
    public Integer anchorDayOfMonth;
    public Boolean isEnabled;
    // ===== 通用 =====
    public Long clientUpdatedAt;
    public Boolean isDeleted;
}
