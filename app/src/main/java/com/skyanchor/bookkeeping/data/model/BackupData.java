package com.skyanchor.bookkeeping.data.model;

import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.entity.UserSettingsEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 一份本地数据快照（V2 新增，开发计划 Phase 7）：备份时的全部 V2 本地数据。
 *
 * <p>实体全部保留原始 id，恢复时按原 id 重插，跨表引用（交易 → 分类 / 账户、
 * 预算 → 分类、周期账单 → 分类 / 账户）才不会断裂。
 */
public final class BackupData {

    /** 备份文件格式版本，与 {@code BackupSerializer.SCHEMA_VERSION} 对齐。 */
    public int schemaVersion;

    @Nullable
    public List<AccountEntity> accounts = new ArrayList<>();

    @Nullable
    public List<CategoryEntity> categories = new ArrayList<>();

    @Nullable
    public List<TransactionEntity> transactions = new ArrayList<>();

    @Nullable
    public List<BudgetEntity> budgets = new ArrayList<>();

    @Nullable
    public List<RecurringTransactionEntity> recurring = new ArrayList<>();

    /** 本地设置单例；备份文件缺失时由恢复侧回落到默认设置。 */
    @Nullable
    public UserSettingsEntity settings;
}
