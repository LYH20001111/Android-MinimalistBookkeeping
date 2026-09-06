package com.skyanchor.bookkeeping.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 账本（V3.2 基线第 3.2 章）：所有业务数据的根节点。
 *
 * <p>本地是服务端账本表的镜像 + 离线创建缓冲：
 * <ul>
 *   <li>账本经同步通道（entityType = LEDGER）创建与删除，改名/归档同样走同步；</li>
 *   <li>成员与角色不走同步（服务端唯一权威），经 REST 拉取后缓存在 {@link #role}；</li>
 *   <li>{@link #isDefault} 标记「迁移回填 / 首次创建」的默认账本：首次推送时服务端
 *       据此把本地默认账本合并（mergedInto）进云端回填的「我的账本」，避免双默认账本；</li>
 *   <li>{@link #isCurrent} 是本地切换状态（不入同步载荷）：业务 DAO 的所有查询都以
 *       「当前账本」子查询过滤，切换 = 翻转标志，Room 自动失效重载全部页面。</li>
 * </ul>
 */
@Entity(tableName = "ledger", indices = {@Index(value = "sync_id", unique = true)})
public class LedgerEntity {

    /** 角色常量（与服务端 LedgerMemberRow 对齐）。 */
    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_MEMBER = "MEMBER";
    public static final String ROLE_VIEWER = "VIEWER";

    /** 本地标记：成员已被移出（服务端 status=REMOVED），账本只读隐藏。 */
    public static final String ROLE_REMOVED = "REMOVED";

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 跨设备稳定身份（UUID）；新建即分配，与服务端账本 syncId 对齐。 */
    @NonNull
    @ColumnInfo(name = "sync_id")
    public String syncId = "";

    @NonNull
    @ColumnInfo(name = "name")
    public String name = "";

    @NonNull
    @ColumnInfo(name = "description", defaultValue = "")
    public String description = "";

    /** 单账本单主币种（基线第 41 章冻结决策 1），V3.2 仅作展示，不参与换算。 */
    @NonNull
    @ColumnInfo(name = "currency", defaultValue = "CNY")
    public String currency = "CNY";

    /** 我在该账本中的角色（REST/status 对账缓存）；REMOVED 表示已被移出。 */
    @NonNull
    @ColumnInfo(name = "role", defaultValue = "OWNER")
    public String role = ROLE_OWNER;

    /** 账本所有者的服务器用户 id（仅展示用，如「所有者」标识）。 */
    @Nullable
    @ColumnInfo(name = "owner_user_id")
    public Long ownerUserId;

    /** 默认账本标记：服务端 claim 合并的依据（见类注释）。 */
    @ColumnInfo(name = "is_default", defaultValue = "0")
    public boolean isDefault;

    @ColumnInfo(name = "is_archived", defaultValue = "0")
    public boolean isArchived;

    /** 账本软删除（仅 OWNER 可操作），与业务数据回收站分离（基线第 12.2 章）。 */
    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    public boolean isDeleted;

    @Nullable
    @ColumnInfo(name = "deleted_at")
    public Long deletedAt;

    /** 本地是否为当前账本（业务查询的过滤锚点，见类注释）。 */
    @ColumnInfo(name = "is_current", defaultValue = "0")
    public boolean isCurrent;

    // ===== 同步元数据（与业务实体同规则）=====

    /** 客户端最后一次从服务器确认的版本；0 = 从未与服务器同步。 */
    @ColumnInfo(name = "version", defaultValue = "0")
    public long version;

    @ColumnInfo(name = "server_received_at", defaultValue = "0")
    public long serverReceivedAt;

    @ColumnInfo(name = "created_at", defaultValue = "0")
    public long createdAt;

    @ColumnInfo(name = "updated_at", defaultValue = "0")
    public long clientUpdatedAt;

    public boolean isLocalOnly() {
        return version == 0;
    }
}
