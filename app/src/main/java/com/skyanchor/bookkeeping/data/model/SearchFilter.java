package com.skyanchor.bookkeeping.data.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;

import java.util.Locale;
import java.util.Objects;

/**
 * 搜索 / 筛选条件（V2 新增，开发计划 Phase 4）。
 *
 * <p>不可变值对象。每个字段都是一个「可选过滤器」，用哨兵值表示「不限制」：
 * <ul>
 *   <li>{@code keyword} 为 null / 空 → 不按关键词过滤；</li>
 *   <li>{@code categoryId} / {@code accountId} 为 0 → 不限分类 / 账户；</li>
 *   <li>{@code minAmount}=0 且 {@code maxAmount}={@link Long#MAX_VALUE} → 不限金额；</li>
 *   <li>{@code startDay}=0 且 {@code endDay}={@link Long#MAX_VALUE} → 不限日期；</li>
 *   <li>三个 {@code includeXxx} 全为 true → 不限类型。</li>
 * </ul>
 *
 * <p>{@link #matches} 是与 {@code TransactionDao.search} 的 SQL WHERE 语义一一对应的纯函数，
 * 作为筛选契约的「可执行规格」供 JVM 单元测试直接覆盖（关键词命中备注 / 分类 / 账户名、
 * 类型集合、分类、账户、金额边界、日期区间），无需依赖数据库即可验证。
 */
public final class SearchFilter {

    /** 分类不限的哨兵：0 不是任何真实分类 id（转账的 categoryId 也经 COALESCE 归 0）。 */
    public static final long NO_CATEGORY = 0L;

    /** 账户不限的哨兵：0 不是任何真实账户 id。 */
    public static final long NO_ACCOUNT = 0L;

    /** 金额下限不限：金额恒为正，0 即「不设下限」。 */
    public static final long NO_MIN_AMOUNT = 0L;

    /** 金额上限不限。 */
    public static final long NO_MAX_AMOUNT = Long.MAX_VALUE;

    /** 关键词，null / 空表示不过滤；命中备注 / 分类名 / 账户名 / 转入账户名。 */
    @Nullable
    public final String keyword;

    /** 日期区间下界（含），当天 00:00 millis；0 表示不限。 */
    public final long startDay;

    /** 日期区间上界（含）；{@link Long#MAX_VALUE} 表示不限。 */
    public final long endDay;

    /** 是否包含支出（type=1）。 */
    public final boolean includeExpense;

    /** 是否包含收入（type=2）。 */
    public final boolean includeIncome;

    /** 是否包含转账（type=3）。 */
    public final boolean includeTransfer;

    /** 分类过滤，0 表示不限。 */
    public final long categoryId;

    /** 账户过滤，0 表示不限；命中转出或转入任一端即算「涉及该账户」。 */
    public final long accountId;

    /** 金额下限（含，分）。 */
    public final long minAmount;

    /** 金额上限（含，分）。 */
    public final long maxAmount;

    private SearchFilter(@NonNull Builder builder) {
        this.keyword = builder.keyword;
        this.startDay = builder.startDay;
        this.endDay = builder.endDay;
        this.includeExpense = builder.includeExpense;
        this.includeIncome = builder.includeIncome;
        this.includeTransfer = builder.includeTransfer;
        this.categoryId = builder.categoryId;
        this.accountId = builder.accountId;
        this.minAmount = builder.minAmount;
        this.maxAmount = builder.maxAmount;
    }

    /** 全不限制的默认过滤器：所有类型 / 分类 / 账户，不限金额与日期，无关键词。 */
    @NonNull
    public static SearchFilter all() {
        return new Builder().build();
    }

    /** 以当前过滤器为基线创建 Builder，便于不可变地改一个字段。 */
    @NonNull
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** 是否有有效关键词（非空且非纯空白）。 */
    public boolean hasKeyword() {
        return keyword != null && !keyword.isEmpty();
    }

    /** 是否完全没有收窄条件，用于 UI 决定是否显示「清空筛选」。 */
    public boolean isUnrestricted() {
        return !hasKeyword()
                && includeExpense && includeIncome && includeTransfer
                && categoryId == NO_CATEGORY
                && accountId == NO_ACCOUNT
                && minAmount == NO_MIN_AMOUNT
                && maxAmount == NO_MAX_AMOUNT
                && startDay == 0L && endDay == Long.MAX_VALUE;
    }

    /**
     * 纯函数：一条账单是否满足本过滤器，与 {@code TransactionDao.search} 的 SQL WHERE 一一对应。
     */
    public boolean matches(@NonNull TransactionItem item) {
        if (item.date < startDay || item.date > endDay) {
            return false;
        }
        if (item.amount < minAmount || item.amount > maxAmount) {
            return false;
        }
        if (!matchesType(item.type)) {
            return false;
        }
        if (categoryId != NO_CATEGORY && item.categoryId != categoryId) {
            return false;
        }
        if (accountId != NO_ACCOUNT && !matchesAccount(item)) {
            return false;
        }
        return !hasKeyword() || matchesKeyword(item);
    }

    private boolean matchesType(int type) {
        if (type == CategoryEntity.TYPE_EXPENSE) {
            return includeExpense;
        }
        if (type == CategoryEntity.TYPE_INCOME) {
            return includeIncome;
        }
        if (type == CategoryEntity.TYPE_TRANSFER) {
            return includeTransfer;
        }
        return false;
    }

    private boolean matchesAccount(@NonNull TransactionItem item) {
        return (item.accountId != null && item.accountId == accountId)
                || (item.transferAccountId != null && item.transferAccountId == accountId);
    }

    private boolean matchesKeyword(@NonNull TransactionItem item) {
        // 与 SQLite LIKE 的 ASCII 大小写折叠一致：用 Locale.US 折叠，非 ASCII（中文）原样保留。
        String needle = Objects.requireNonNull(keyword).toLowerCase(Locale.US);
        return contains(item.note, needle)
                || contains(item.categoryName, needle)
                || contains(item.accountName, needle)
                || contains(item.transferAccountName, needle);
    }

    private static boolean contains(@Nullable String source, @NonNull String lowerNeedle) {
        return source != null && source.toLowerCase(Locale.US).contains(lowerNeedle);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SearchFilter)) {
            return false;
        }
        SearchFilter other = (SearchFilter) o;
        return startDay == other.startDay
                && endDay == other.endDay
                && includeExpense == other.includeExpense
                && includeIncome == other.includeIncome
                && includeTransfer == other.includeTransfer
                && categoryId == other.categoryId
                && accountId == other.accountId
                && minAmount == other.minAmount
                && maxAmount == other.maxAmount
                && Objects.equals(keyword, other.keyword);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyword, startDay, endDay, includeExpense, includeIncome,
                includeTransfer, categoryId, accountId, minAmount, maxAmount);
    }

    @Override
    @NonNull
    public String toString() {
        return "SearchFilter{kw=" + keyword + ", type=" + includeExpense + includeIncome
                + includeTransfer + ", cat=" + categoryId + ", acc=" + accountId
                + ", amount=" + minAmount + ".." + maxAmount + ", date=" + startDay + ".." + endDay
                + "}";
    }

    /** {@link SearchFilter} 的可变构造器，字段默认值即「全不限制」。 */
    public static final class Builder {

        @Nullable
        private String keyword;
        private long startDay = 0L;
        private long endDay = Long.MAX_VALUE;
        private boolean includeExpense = true;
        private boolean includeIncome = true;
        private boolean includeTransfer = true;
        private long categoryId = NO_CATEGORY;
        private long accountId = NO_ACCOUNT;
        private long minAmount = NO_MIN_AMOUNT;
        private long maxAmount = NO_MAX_AMOUNT;

        public Builder() {
        }

        /** 以既有过滤器为基线复制，便于只改一个字段。 */
        public Builder(@NonNull SearchFilter base) {
            this.keyword = base.keyword;
            this.startDay = base.startDay;
            this.endDay = base.endDay;
            this.includeExpense = base.includeExpense;
            this.includeIncome = base.includeIncome;
            this.includeTransfer = base.includeTransfer;
            this.categoryId = base.categoryId;
            this.accountId = base.accountId;
            this.minAmount = base.minAmount;
            this.maxAmount = base.maxAmount;
        }

        /** 设置关键词，空白归一为 null（对齐 SQL 的 {@code :keyword IS NULL} 分支）。 */
        @NonNull
        public Builder keyword(@Nullable String value) {
            this.keyword = emptyToNull(value);
            return this;
        }

        @NonNull
        public Builder dateRange(long start, long end) {
            this.startDay = start;
            this.endDay = end;
            return this;
        }

        /** 设置包含的交易类型；三者全 false 时不会命中任何账单，UI 应传「全 true」表示不限。 */
        @NonNull
        public Builder types(boolean expense, boolean income, boolean transfer) {
            this.includeExpense = expense;
            this.includeIncome = income;
            this.includeTransfer = transfer;
            return this;
        }

        @NonNull
        public Builder categoryId(long value) {
            this.categoryId = value;
            return this;
        }

        @NonNull
        public Builder accountId(long value) {
            this.accountId = value;
            return this;
        }

        @NonNull
        public Builder amountRange(long min, long max) {
            this.minAmount = min;
            this.maxAmount = max;
            return this;
        }

        @NonNull
        public SearchFilter build() {
            return new SearchFilter(this);
        }

        @Nullable
        private static String emptyToNull(@Nullable String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }
}
