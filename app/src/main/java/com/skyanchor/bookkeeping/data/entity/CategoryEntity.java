package com.skyanchor.bookkeeping.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import java.util.Objects;

/**
 * 类别表。支出与收入分类通过 {@link #type} 区分。
 * icon 存放 emoji 字符，与 V1 基线第 6 章的默认分类保持一致。
 */
@Entity(tableName = "category", indices = {@Index(value = "sync_id")})
public class CategoryEntity {

    /** 支出类型。 */
    public static final int TYPE_EXPENSE = 1;
    /** 收入类型。 */
    public static final int TYPE_INCOME = 2;
    /**
     * 转账类型（V2 新增）。转账不归属任何分类（category_id 为 NULL），
     * 既不计收入也不计支出，只在两个账户之间搬动余额。
     */
    public static final int TYPE_TRANSFER = 3;

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "name")
    public String name = "";

    /** 图标标识，V1 使用 emoji 字符串。 */
    @NonNull
    @ColumnInfo(name = "icon")
    public String icon = "";

    /** 1=支出，2=收入。 */
    @ColumnInfo(name = "type")
    public int type = TYPE_EXPENSE;

    @ColumnInfo(name = "sort_order")
    public int sortOrder;

    @ColumnInfo(name = "is_default")
    public boolean isDefault;


    // ===== V3 同步元数据（基线第 14 章）=====

    /** 跨设备稳定身份（UUID）；本地行入库时即分配，与本地自增 id 职责分离。 */
    @NonNull
    @ColumnInfo(name = "sync_id", defaultValue = "")
    public String syncId = "";

    /** 客户端最后一次从服务器确认的版本；0 = 从未与服务器同步。 */
    @ColumnInfo(name = "version", defaultValue = "0")
    public long version;

    /** 服务器最后一次确认该行的时间（epoch millis）；0 = 从未同步。 */
    @ColumnInfo(name = "server_received_at", defaultValue = "0")
    public long serverReceivedAt;

    /** Soft Delete 标记（基线第 17 章）：删除 = 置位 + 版本递增，作为可同步事件传播。 */
    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    public boolean isDeleted;

    public CategoryEntity() {
    }

    @Ignore
    public CategoryEntity(@NonNull String name, @NonNull String icon, int type, int sortOrder,
                          boolean isDefault) {
        this.name = name;
        this.icon = icon;
        this.type = type;
        this.sortOrder = sortOrder;
        this.isDefault = isDefault;
    }

    /** 供 DiffUtil 判断内容是否变化，Room 不关心这两个方法。 */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CategoryEntity)) {
            return false;
        }
        CategoryEntity other = (CategoryEntity) o;
        return id == other.id
                && type == other.type
                && sortOrder == other.sortOrder
                && isDefault == other.isDefault
                && Objects.equals(name, other.name)
                && Objects.equals(icon, other.icon);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, icon, type, sortOrder, isDefault);
    }
}
