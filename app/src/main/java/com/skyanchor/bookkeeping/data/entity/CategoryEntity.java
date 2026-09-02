package com.skyanchor.bookkeeping.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import java.util.Objects;

/**
 * 类别表。支出与收入分类通过 {@link #type} 区分。
 * icon 存放 emoji 字符，与 V1 基线第 6 章的默认分类保持一致。
 */
@Entity(tableName = "category")
public class CategoryEntity {

    /** 支出类型。 */
    public static final int TYPE_EXPENSE = 1;
    /** 收入类型。 */
    public static final int TYPE_INCOME = 2;

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
