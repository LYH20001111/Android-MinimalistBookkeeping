package com.skyanchor.bookkeeping.data.entity;

/** 同步协议的实体类型与操作常量（Sync Protocol Version 1，与服务端对齐）。 */
public final class SyncEntityTypes {

    public static final String CATEGORY = "CATEGORY";
    public static final String ACCOUNT = "ACCOUNT";
    public static final String TRANSACTION = "TRANSACTION";
    public static final String BUDGET = "BUDGET";
    public static final String RECURRING = "RECURRING";

    public static final String OP_UPSERT = "UPSERT";
    public static final String OP_DELETE = "DELETE";

    private SyncEntityTypes() {
    }
}
