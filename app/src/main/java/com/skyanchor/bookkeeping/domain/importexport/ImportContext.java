package com.skyanchor.bookkeeping.domain.importexport;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CSV 导入上下文（V2 新增，开发计划 Phase 5）。纯数据 + 纯查表，无 Android 依赖，可 JVM 单测。
 *
 * <p>把导入所需的「参照数据」一次性快照下来，供 {@code ImportRowClassifier} 逐行查表：
 * <ul>
 *   <li>分类：按「类型 + 名称」映射到 id，支出 / 收入同名分类互不串味；</li>
 *   <li>账户：按名称映射到 id（含已归档，保证历史账单可回流）；</li>
 *   <li>指纹：库中既有交易的「疑似重复」键集合，用于导入去重，杜绝静默重复写入。</li>
 * </ul>
 *
 * <p>指纹口径与开发计划一致：{@code 类型 + 日期 + 时间 + 金额 + 分类 + 账户 + 转入账户 + 备注}
 * 全部相同即视为疑似重复（较计划的单账户口径更严格地纳入类型与转入账户，只会少判、不会误判）。
 */
public final class ImportContext {

    /** 分类查表键分隔符，取一个不会出现在名称里的控制字符。 */
    private static final char KEY_SEP = '\u0000';

    private final Map<String, Long> categoryIds = new HashMap<>();
    private final Map<String, Long> accountIds = new HashMap<>();
    private final Set<String> existingFingerprints = new HashSet<>();

    public ImportContext(@Nullable List<CategoryEntity> categories,
                         @Nullable List<AccountEntity> accounts,
                         @Nullable List<TransactionEntity> existing) {
        if (categories != null) {
            for (CategoryEntity category : categories) {
                categoryIds.put(categoryKey(category.type, category.name), category.id);
            }
        }
        if (accounts != null) {
            for (AccountEntity account : accounts) {
                accountIds.put(account.name, account.id);
            }
        }
        if (existing != null) {
            for (TransactionEntity t : existing) {
                existingFingerprints.add(fingerprint(t.type, t.date, t.time, t.amount,
                        t.categoryId, t.accountId, t.transferAccountId, t.note));
            }
        }
    }

    /** 按「类型 + 名称」查分类 id，不存在返回 null。 */
    @Nullable
    public Long categoryId(int type, @NonNull String name) {
        return categoryIds.get(categoryKey(type, name));
    }

    /** 按名称查账户 id，不存在返回 null。 */
    @Nullable
    public Long accountId(@NonNull String name) {
        return accountIds.get(name);
    }

    /** 该指纹是否已存在于库中（疑似重复）。 */
    public boolean isDuplicate(@NonNull String fingerprint) {
        return existingFingerprints.contains(fingerprint);
    }

    @NonNull
    private static String categoryKey(int type, @NonNull String name) {
        return type + String.valueOf(KEY_SEP) + name;
    }

    /**
     * 构建「疑似重复」指纹。空值统一归一（null 分类 / 账户记 0、null 备注记空串），
     * 使「导出→导入」与「库中既有」两侧口径一致，能稳定命中重复。
     */
    @NonNull
    public static String fingerprint(int type, long date, @Nullable String time, long amount,
                                     @Nullable Long categoryId, @Nullable Long accountId,
                                     @Nullable Long transferAccountId, @Nullable String note) {
        return type + "|" + date + "|" + (time == null ? "" : time) + "|" + amount + "|"
                + (categoryId == null ? 0L : categoryId) + "|"
                + (accountId == null ? 0L : accountId) + "|"
                + (transferAccountId == null ? 0L : transferAccountId) + "|"
                + (note == null ? "" : note);
    }
}
